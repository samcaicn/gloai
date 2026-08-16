// Copyright (c) 2026 AIMarketing
//
// AIMarketing v5 — 三级记忆架构的「情景记忆(episodic memory)」层。
//
// 设计决策(doc comment):
//   * 单条 `EpRecord` 描述"某次 skill 执行中某一步的实际发生了什么":
//     命中的 selector、用到的 strategy、outcome、错误、VLM prompt/response
//     (关键反思材料)、截图哈希、本步耗时等。
//   * 数据模型刻意做成"扁平 + 标量"而不是嵌套对象,这样后续
//     `SqliteEpisodicStore` 落表时不需要额外的 JSON 列;`serde` 也好
//     序列化进 `JsonlExporter` 训练数据流。
//   * 字段全部 `pub`,因为这是事实上的"内层数据模型",所有上层
//     (executor / recorder / replay / trajectory exporter)都需要直接
//     读写;为了规避"半 pub 半私"的歧义,这里直接全 pub。
//   * `outcome` 故意保留为 `String` 而不是枚举,理由:
//     (1) 字符串字面量可直接进 prompt / log / 训练数据,无需额外映射;
//     (2) 后续 v6 / v7 新增 outcome 类型时不会破坏反序列化。
//
// 命名约定: doc1 §2 + 配合 `pc_automation::executor::ExecutionReceipt`:
//   * `exec_id` — 对齐 `ExecutionReceipt::exec_id`
//   * `step_id` — 对齐 `SkillStep::id`

use serde::{Deserialize, Serialize};

/// 一条情景记忆。详情见本模块文件头 doc comment。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EpRecord {
    /// 全局唯一 uuid。`uuid::Uuid::new_v4().to_string()` 即可。
    pub id: String,
    /// 记录时间,unix 毫秒。生成时机是"步骤刚结束",而不是"步骤开始"。
    pub timestamp: i64,
    /// 关联 `ExecutionReceipt::exec_id`。
    pub exec_id: String,
    /// 关联 `SkillStep::id`。
    pub step_id: String,
    /// `pc_automation::apps::AppProfile::id`,例如 `"ths_hexin"`。
    /// `None` 表示尚未绑定到具体 app(例如 dry-run)。
    pub app_profile: Option<String>,
    /// 用户的本步意图(从 `SkillStep::intent` 透传)。反思检索的关键词。
    pub intent: String,
    /// 实际命中并执行的那个 selector 字符串(例如
    /// `"uia:controlType=Button;name=提交"`)。`None` 表示
    /// 所有 selector 都 miss(走 VLM rescue / error chain 也没救)。
    pub selector_used: Option<String>,
    /// 真正用到的 strategy。沿用 `StepStrategy` 的小写串:
    /// `Uia | Cdp | Ocr | Vlm`。
    pub strategy_used: String,
    /// 五种 outcome 之一:
    ///   * `success`         — 一次命中,无 VLM 介入
    ///   * `primary_miss`    — 主 tier 都没命中(structured_miss 的子集)
    ///   * `structured_miss` — 主 + fallback 都 miss,触发 VLM 或 error chain
    ///   * `vlm_rescued`     — 靠 VLM 救回来了
    ///   * `failed`          — 真的失败,executor 放弃本步
    pub outcome: String,
    /// 失败时的错误消息。`outcome == success` 时为 `None`。
    pub error: Option<String>,
    /// 截图 SHA256 哈希(十六进制)。本期不真正落截图,所以为 `None`
    /// 居多;后续 `vlm_rescue::screenshot` 接入后可填上。
    pub screenshot_hash: Option<String>,
    /// 实际发给 VLM 的 prompt 全文。关键反思材料:
    ///   * 反思 agent 拿到这条 record,就能反推"当时我们是怎么问 VLM 的",
    ///     进而发现 prompt 设计的盲点。
    pub vlm_prompt: Option<String>,
    /// VLM 的原始 response 字符串。配对 `vlm_prompt` 使用,
    /// 反思 agent 可以做 "response 跟实际意图是否匹配" 的二次分析。
    pub vlm_response: Option<String>,
    /// 本步总耗时,毫秒。从 executor 接住 step 到 step 结束的
    /// `step_durations_ms[i]`,而不是路由器单次调用耗时。
    pub latency_ms: u64,
}

impl EpRecord {
    /// 一个便利构造器,自动生成 `id = uuid::Uuid::new_v4().to_string()`。
    /// 实际项目里 executor 可以在 step 结束时调用它。
    pub fn new(
        timestamp: i64,
        exec_id: impl Into<String>,
        step_id: impl Into<String>,
        intent: impl Into<String>,
        outcome: impl Into<String>,
    ) -> Self {
        Self {
            id: uuid::Uuid::new_v4().to_string(),
            timestamp,
            exec_id: exec_id.into(),
            step_id: step_id.into(),
            app_profile: None,
            intent: intent.into(),
            selector_used: None,
            strategy_used: String::new(),
            outcome: outcome.into(),
            error: None,
            screenshot_hash: None,
            vlm_prompt: None,
            vlm_response: None,
            latency_ms: 0,
        }
    }
}

// ============================================================================
// Unit tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// EpRecord 必须能以 camelCase JSON 序列化。front-end + trajectory
    /// exporter 都依赖这一点。
    #[test]
    fn test_record_serialization_camel_case() {
        let mut rec = EpRecord::new(1_700_000_000_000, "exec-1", "step-1", "提交订单", "success");
        rec.app_profile = Some("ths_hexin".to_string());
        rec.selector_used = Some("uia:controlType=Button;name=提交".to_string());
        rec.strategy_used = "uia".to_string();
        rec.latency_ms = 123;

        let v: serde_json::Value = serde_json::to_value(&rec).unwrap();
        // camelCase 字段名断言(枚举值是字符串,字段也是字符串)
        assert!(v.get("execId").is_some(), "must serialise as execId, got: {}", v);
        assert!(v.get("stepId").is_some());
        assert!(v.get("appProfile").is_some());
        assert!(v.get("selectorUsed").is_some());
        assert!(v.get("strategyUsed").is_some());
        assert!(v.get("screenshotHash").is_some());
        assert!(v.get("vlmPrompt").is_some());
        assert!(v.get("vlmResponse").is_some());
        assert!(v.get("latencyMs").is_some());
        // 不应出现 snake_case 形式
        let raw = serde_json::to_string(&rec).unwrap();
        assert!(!raw.contains("\"exec_id\""), "snake_case leaked: {}", raw);
        assert!(!raw.contains("\"app_profile\""), "snake_case leaked: {}", raw);
        assert!(!raw.contains("\"selector_used\""), "snake_case leaked: {}", raw);

        // 反序列化能完整还原
        let back: EpRecord = serde_json::from_str(&raw).unwrap();
        assert_eq!(back, rec);

        // 顺便确认 JSON 形状可以被前端的 `Record<string, unknown>` 消费
        let expected_subset = json!({
            "execId": "exec-1",
            "stepId": "step-1",
            "intent": "提交订单",
            "outcome": "success",
            "strategyUsed": "uia",
            "latencyMs": 123,
        });
        for (k, v_expected) in expected_subset.as_object().unwrap() {
            assert_eq!(v.get(k).unwrap(), v_expected, "field {} mismatch", k);
        }
    }
}
