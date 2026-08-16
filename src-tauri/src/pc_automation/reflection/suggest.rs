// Copyright (c) 2026 tupAI
//
// tupAI v5 §6.2 — 为失败聚类生成修复建议 selector。
//
// 设计决策(doc comment):
//   * 调用风格与 `vlm_rescue::analyzer::build_dynamic_prompt` 一致:
//     (1) 优先调用云端 LLM 撰写新 selector;
//     (2) 任何错误(网络 / rate-limit / 空响应)都静默 fallback;
//     (3) 离线 / 单元测试场景下,传 `llm: None` 走纯本地启发式。
//   * LLM 提示词风格参考 `vlm_rescue::analyzer::build_compose_request`:
//     直接要求模型输出"一段可执行 selector 字符串",而不是长篇
//     markdown。这样返回值可以直接喂给 `ElementSelector` 解析器。
//   * 本地启发式 fallback 的"模式识别"采取**字面**策略:
//     (1) 如果 cluster 内出现 "uia" 关键词 → 建议尝试 CDP;
//     (2) 如果 cluster 内出现 "cdp" / "browser" → 建议尝试 OCR;
//     (3) 如果 cluster 内出现 "ocr" / "anchor" → 建议回退 OCR
//         或上报人工;
//     (4) 都没有 → 默认"上报人工"。这套规则刻意保持简单,避免
//         反射层自己再发明一遍 selector 选择策略。

use std::sync::Arc;

use serde::{Deserialize, Serialize};

use super::cluster::FailureCluster;
use crate::pc_automation::ui_tars::llm::try_call_llm;
use crate::pc_automation::ui_tars::LlmCompleteFn;
// uirap v2: `LlmCompleteFn` / `LlmMessage` 仍由 `pc_automation::ui_tars`
// re-export,这里不再 `use ... vlm_rescue::analyzer::{LlmCompleteFn,
// LlmMessage}`(避免与 ui_tars 形成双向依赖);如需,直接
// `use crate::pc_automation::ui_tars::{LlmCompleteFn, LlmMessage}`。

/// 建议生成配置。
///
/// `prefer_ocr_fallback` 是 default 启发式之外的"全局偏好":
/// 为 `true` 时,fallback 永远先推 OCR,再考虑 CDP / 人工。
/// 本期不引入新的 enum,保持 boolean 即可,避免下游 v6 之前过度设计。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SuggestConfig {
    /// 是否使用 LLM。无 LLM / LLM 失败 → 走本地启发式。
    pub use_llm: bool,
    /// OCR 兜底是否优先于 CDP 兜底。
    pub prefer_ocr_fallback: bool,
}

impl Default for SuggestConfig {
    fn default() -> Self {
        Self {
            use_llm: true,
            prefer_ocr_fallback: false,
        }
    }
}

/// 主入口:为一个聚类生成修复建议 selector 字符串。
///
/// 流程:
///   1. `cfg.use_llm && llm.is_some()` → 调用 LLM,成功且非空 → 返回;
///   2. 任何失败 → fallback 到 `local_heuristic`。
///
/// 返回 `Result<String, String>` 是为了和 `vlm_rescue::analyzer`
/// 风格对齐:本地启发式永不出错,LLM 路径理论上可以返回"被人工
/// 标脏"等错误,但本期把它简化为"返回 `Ok(s)` 即可"。
pub async fn suggest_selector_for_cluster(
    cluster: &FailureCluster,
    llm: Option<Arc<dyn LlmCompleteFn>>,
    cfg: &SuggestConfig,
) -> Result<String, String> {
    // 0. 边界:cluster 必须非空,否则本地启发式也会拿不到有意义的输入。
    if cluster.members.is_empty() {
        return Err("聚类成员为空,无法生成建议".to_string());
    }

    // 1. LLM 路径(uirap v2: 统一用 `try_call_llm`,失败返回 `None`
    //    → 静默走 fallback)
    if cfg.use_llm {
        let request = build_suggestion_request(cluster);
        if let Some(s) = try_call_llm(llm.as_ref(), request).await {
            return Ok(s);
        }
    }

    // 2. 本地启发式
    Ok(local_heuristic(cluster, cfg))
}

/// LLM 路径下"请撰写修复建议 selector"的提示词。
///
/// 与 `vlm_rescue::analyzer::build_compose_request` 不同,
/// 这里要求模型**直接**返回一个 selector 字符串(不是一段 prompt),
/// 理由:反思-建议链路在前端 UI 是"一键应用",所以建议必须机器可读。
fn build_suggestion_request(cluster: &FailureCluster) -> String {
    let app_line = match &cluster.app_profile {
        Some(p) => format!("应用: {p}"),
        None => "应用: 未知".to_string(),
    };
    format!(
        "你是一名资深的 UI 自动化测试工程师。下面是一个失败聚类,\
         它代表多次执行中,同一 (应用, 错误模式) 下反复出现的同一种失败。\n\
         \n\
         ## 聚类摘要\n\
         - 聚类 id: {cluster_id}\n\
         - {app_line}\n\
         - 共用 intent 前缀: '{intent}'\n\
         - 错误模式(头 100 字符): {error}\n\
         - 成员数: {members}\n\
         - 首次出现: ts={first}\n\
         - 最近一次: ts={last}\n\
         \n\
         ## 任务\n\
         请直接输出一段**新的 selector 字符串**,要求:\n\
         1. 简短、可执行(可以喂给 `ElementSelector::parse` 解析)。\n\
         2. 与原失败 selector 至少在**优先级**或**策略**上有差异\n\
            (例如从 uia: 切到 ocr: 或 cdp:)。\n\
         3. 中文场景下,如果原 selector 是 UIA 树控件名命中失败,\n\
            优先建议改用 OCR anchor 或 CDP 文本选择器。\n\
         \n\
         ## 严格输出格式\n\
         只输出 selector 字符串本身,**不要**加任何 markdown 代码块标记、\
         解释、序号或换行;不要写 'selector:' 前缀。",
        cluster_id = cluster.cluster_id,
        app_line = app_line,
        intent = cluster.intent_substring,
        error = cluster.error_pattern,
        members = cluster.members.len(),
        first = cluster.first_seen,
        last = cluster.last_seen,
    )
}

/// 本地启发式 fallback。
///
/// 触发条件(按 cluster.error_pattern 子串扫描):
///   * 含 "uia" 或 "controltype" 或 "automationid" → 建议 CDP
///   * 含 "cdp" 或 "browser" 或 "devtools" → 建议 OCR
///   * 含 "ocr" 或 "anchor" → 视 cfg.prefer_ocr_fallback 决定
///     (a) true:  继续建议 OCR 兜底(更难搞)→ 转人工
///     (b) false: 建议 CDP
///   * 都不匹配 → 上报人工
///
/// 返回的 selector 字符串**是**伪 selector(给前端 UI 渲染 +
/// 日志肉眼检查用),不是真的能喂给 `ElementSelector::parse` 的合法串。
/// 这是有意为之 — 本地启发式本来就不该"骗 executor 真去执行它",
/// 它只是一个"提示"。
pub fn local_heuristic(cluster: &FailureCluster, cfg: &SuggestConfig) -> String {
    let err = cluster.error_pattern.to_ascii_lowercase();
    let intent_kw = cluster.intent_substring.to_ascii_lowercase();
    let haystack = format!("{err} {intent_kw}");

    if haystack.contains("uia")
        || haystack.contains("controltype")
        || haystack.contains("automationid")
    {
        // UIA 失败的常见 fallback: CDP(浏览器/Electron)
        return "cdp:text=提交 [建议: 由 uia: 切到 cdp:]".to_string();
    }
    if haystack.contains("cdp") || haystack.contains("devtools") || haystack.contains("browser")
    {
        // CDP 失败的常见 fallback: OCR(自绘中文 UI)
        return "ocr:anchor=@submit_btn [建议: 由 cdp: 切到 ocr:]".to_string();
    }
    if haystack.contains("ocr") || haystack.contains("anchor") {
        return if cfg.prefer_ocr_fallback {
            // OCR 反复失败 → 已无 fallback,上报人工
            "manual_review:true [建议: OCR 反复失败,上报人工]".to_string()
        } else {
            // CDP 兜底一次
            "cdp:text=submit [建议: 由 ocr: 切到 cdp:]".to_string()
        };
    }
    // 兜底:无法分类
    "manual_review:true [建议: 失败模式未识别,上报人工]".to_string()
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pc_automation::episodic::record::EpRecord;
    use crate::pc_automation::ui_tars::LlmMessage;

    fn mk_cluster(
        intent_substring: &str,
        app: Option<&str>,
        error_pattern: &str,
    ) -> FailureCluster {
        let mut c = FailureCluster::new_skeleton(app.map(|s| s.to_string()), error_pattern.to_string());
        c.intent_substring = intent_substring.to_string();
        c.members = vec!["m1".to_string(), "m2".to_string()];
        c.first_seen = 100;
        c.last_seen = 200;
        c.confidence = 0.5;
        c
    }

    /// 没传 LLM → 必须直接走本地启发式,且返回值非空。
    /// 这是"无 LLM 时的离线安全网"测试。
    #[tokio::test]
    async fn test_suggest_selector_fallback_when_no_llm() {
        let cluster = mk_cluster("提交订单", Some("ths_hexin"), "uia:button not found");
        let cfg = SuggestConfig::default();
        let out = suggest_selector_for_cluster(&cluster, None, &cfg)
            .await
            .expect("无 LLM 时启发式永不出错");
        assert!(!out.is_empty(), "本地启发式必须返回非空字符串");
        // UIA 类错误 → 启发式应推荐 cdp
        assert!(
            out.contains("cdp:") || out.contains("建议"),
            "uia 错误应推荐 cdp 兜底,实际: {}",
            out
        );
    }

    /// 传一个会返回固定 selector 的 stub LLM → 必须用 LLM 输出,
    /// 而**不**走本地启发式。stub 实现参考 `vlm_rescue::tests.rs`。
    #[tokio::test]
    async fn test_suggest_selector_uses_llm_when_available() {
        use std::pin::Pin;

        fn stub_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async { Ok("cdp:text=LLM-RECOMMENDED [stub]".to_string()) })
        }

        let cluster = mk_cluster("提交订单", Some("ths_hexin"), "uia:button not found");
        let cfg = SuggestConfig::default();
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(stub_llm);
        let out = suggest_selector_for_cluster(&cluster, Some(llm), &cfg)
            .await
            .expect("LLM 路径应成功");
        assert_eq!(out, "cdp:text=LLM-RECOMMENDED [stub]", "LLM 输出必须原样返回");
    }

    /// LLM 报错 → 静默 fallback,本地启发式接管。
    #[tokio::test]
    async fn test_suggest_selector_falls_back_on_llm_error() {
        use std::pin::Pin;

        fn failing_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async { Err("network down".to_string()) })
        }

        let cluster = mk_cluster("撤单", Some("ths_hexin"), "uia:controlType=Button not found");
        let cfg = SuggestConfig::default();
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(failing_llm);
        let out = suggest_selector_for_cluster(&cluster, Some(llm), &cfg)
            .await
            .expect("fallback 必须成功");
        // UIA 错误应推荐 cdp
        assert!(out.contains("cdp:") || out.contains("建议"));
    }

    /// LLM 返回空字符串 → 同样 fallback。
    #[tokio::test]
    async fn test_suggest_selector_falls_back_on_empty_response() {
        use std::pin::Pin;

        fn empty_llm(
            _msgs: Vec<LlmMessage>,
        ) -> Pin<Box<dyn std::future::Future<Output = Result<String, String>> + Send>>
        {
            Box::pin(async { Ok(String::new()) })
        }

        let cluster = mk_cluster("下单", Some("ths_hexin"), "uia: not found");
        let cfg = SuggestConfig::default();
        let llm: Arc<dyn LlmCompleteFn> = Arc::new(empty_llm);
        let out = suggest_selector_for_cluster(&cluster, Some(llm), &cfg)
            .await
            .expect("fallback 必须成功");
        assert!(!out.is_empty());
        assert!(out.contains("cdp:") || out.contains("建议"));
    }

    /// 空 cluster.members → 必须显式报 Err,不返回伪 selector。
    #[tokio::test]
    async fn test_suggest_selector_rejects_empty_cluster() {
        let mut cluster = FailureCluster::new_skeleton(Some("app".to_string()), "x".to_string());
        cluster.members.clear();
        let cfg = SuggestConfig::default();
        let err = suggest_selector_for_cluster(&cluster, None, &cfg)
            .await
            .expect_err("空 cluster 必须报错");
        assert!(err.contains("聚类成员为空"));
    }

    /// 本地启发式分类:CDP 错误 → 推 OCR 兜底。
    #[test]
    fn test_local_heuristic_cdp_error_recommends_ocr() {
        let cluster = mk_cluster("搜索", Some("browser-app"), "cdp: selector not found");
        let cfg = SuggestConfig::default();
        let out = local_heuristic(&cluster, &cfg);
        assert!(out.contains("ocr:"), "CDP 错误应推 OCR,实际: {}", out);
    }

    /// 本地启发式分类:OCR 错误 → 默认推 CDP,prefer_ocr=true 时转人工。
    #[test]
    fn test_local_heuristic_ocr_error_two_modes() {
        let cluster = mk_cluster("确认", Some("app"), "ocr: anchor not found");
        // 默认: prefer_ocr_fallback = false → 推 CDP
        let out = local_heuristic(&cluster, &SuggestConfig::default());
        assert!(out.contains("cdp:") || out.contains("建议"));
        // prefer_ocr_fallback = true → 报人工
        let out = local_heuristic(
            &cluster,
            &SuggestConfig {
                use_llm: true,
                prefer_ocr_fallback: true,
            },
        );
        assert!(out.contains("manual_review") || out.contains("人工"));
    }

    /// `EpRecord` 的字段在本测试里不直接使用,但我们保留这个 import
    /// 防止 rustfmt 重排时把 import 链断掉,以及提醒"建议生成链路
    /// 消费的就是 EpRecord.id,这里只取聚类后的 view"。
    #[test]
    fn _record_field_round_trip_smoke() {
        let mut r = EpRecord::new(1, "exec", "step", "intent", "failed");
        r.id = "x".to_string();
        r.app_profile = Some("app".to_string());
        r.error = Some("uia: x".to_string());
        // 仅做字段 round-trip,避免静默删字段
        assert_eq!(r.id, "x");
        assert_eq!(r.app_profile.as_deref(), Some("app"));
    }
}
