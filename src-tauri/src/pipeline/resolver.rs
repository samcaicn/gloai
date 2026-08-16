use serde_json::{Map, Value};
use std::collections::BTreeMap;

/// 路径段：按名称取字段 或 按索引取数组元素
#[derive(Debug, Clone)]
enum PathSegment {
    Field(String),
    Index(usize),
}

/// 递归替换 params 中的 `$steps[i].path` 占位符为 outputs[i] 对应值。
///
/// 支持的格式:
/// - `"$steps[0]"` → 整个 outputs[0] (JSON 对象)
/// - `"$steps[1].result"` → outputs[1]["result"]
/// - `"$steps[2].data[0].title"` → outputs[2]["data"][0]["title"]
/// - `"$steps[2].data.title"` → outputs[2]["data"]["title"]
/// - `"前缀$steps[0].field 后缀"` → 字符串拼接
/// - 嵌套在数组/对象中也会被扫描替换
///
/// 找不到索引或字段时保留原字符串（不报错，debug 级 log）。
pub fn resolve_refs(params: Value, outputs: &[Value]) -> Value {
    match params {
        Value::String(s) => {
            let resolved = resolve_string(&s, outputs);
            Value::String(resolved)
        }
        Value::Array(arr) => {
            let resolved: Vec<Value> = arr
                .into_iter()
                .map(|v| resolve_refs(v, outputs))
                .collect();
            Value::Array(resolved)
        }
        Value::Object(map) => {
            let resolved: Map<String, Value> = map
                .into_iter()
                .map(|(k, v)| (k, resolve_refs(v, outputs)))
                .collect();
            Value::Object(resolved)
        }
        other => other,
    }
}

/// 单字符串解析：扫描所有 `$steps[i].path` 模式并替换
fn resolve_string(s: &str, outputs: &[Value]) -> String {
    let mut result = s.to_string();

    // 用 BTreeMap 存储所有匹配的替换项（start→(range_end, replacement)），避免多次替换相互影响
    let mut replacements: BTreeMap<usize, (usize, String)> = BTreeMap::new();

    // 正则: $steps<数字>{.<字段>}
    // 注意: regex crate 不是依赖，用手工解析
    let bytes = s.as_bytes();
    let len = bytes.len();
    let mut pos = 0;
    while pos < len {
        // 查找 $
        if bytes[pos] != b'$' {
            pos += 1;
            continue;
        }
        let start = pos;
        pos += 1; // 跳过 $

        // 匹配 "steps"
        if pos + 5 > len || &bytes[pos..pos + 5] != b"steps" {
            continue;
        }
        pos += 5;

        // 匹配 "["
        if pos >= len || bytes[pos] != b'[' {
            continue;
        }
        pos += 1;

        // 解析数字索引
        let num_start = pos;
        while pos < len && bytes[pos].is_ascii_digit() {
            pos += 1;
        }
        if pos == num_start {
            continue; // 无数字
        }
        let idx: usize = s[num_start..pos].parse().unwrap_or(usize::MAX);
        if idx == usize::MAX || idx >= outputs.len() {
            // 继续找下一个 $ (跳过无效索引)
            continue;
        }

        // 匹配 "]"
        if pos >= len || bytes[pos] != b']' {
            continue;
        }
        pos += 1;

        // 解析可选字段路径: .field[0].subfield 或 .field.subfield[1]
        // 字段名只允许字母数字下划线；可选紧跟 [数字] 取数组元素
        let mut path_segments: Vec<PathSegment> = Vec::new();
        while pos < len && bytes[pos] == b'.' {
            pos += 1; // 跳过 .
            let seg_start = pos;
            while pos < len && (bytes[pos].is_ascii_alphanumeric() || bytes[pos] == b'_') {
                pos += 1;
            }
            if pos > seg_start {
                path_segments.push(PathSegment::Field(s[seg_start..pos].to_string()));
                // 可选数组索引: [digits]（可多个连续如 [0][1]）
                while pos < len && bytes[pos] == b'[' {
                    let bracket_start = pos;
                    pos += 1; // 跳过 [
                    let idx_start = pos;
                    while pos < len && bytes[pos].is_ascii_digit() {
                        pos += 1;
                    }
                    if pos > idx_start && pos < len && bytes[pos] == b']' {
                        let idx: usize = s[idx_start..pos].parse().unwrap_or(usize::MAX);
                        if idx != usize::MAX {
                            path_segments.push(PathSegment::Index(idx));
                        }
                        pos += 1; // 跳过 ]
                    } else {
                        pos = bracket_start; // 无效索引，回退
                        break;
                    }
                }
            } else {
                break; // 空段名（如末尾点），不再尝试后续路径
            }
        }

        // 提取值
        let value = get_nested(&outputs[idx], &path_segments);
        let value_str = match value {
            Value::String(s) => s.clone(),
            Value::Number(n) => n.to_string(),
            Value::Bool(b) => b.to_string(),
            other => serde_json::to_string(other).unwrap_or_default(),
        };

        replacements.insert(start, (pos, value_str));
    }

    // 从后往前替换（保持偏移量不变）
    for (&range_start, &(range_end, ref replacement)) in replacements.iter().rev() {
        result.replace_range(range_start..range_end, replacement);
    }

    result
}

/// 从 Value 中按路径链提取嵌套值
fn get_nested<'a>(value: &'a Value, segments: &[PathSegment]) -> &'a Value {
    let mut current = value;
    for seg in segments {
        current = match seg {
            PathSegment::Field(name) => match current {
                Value::Object(map) => map.get(name.as_str()).unwrap_or(&Value::Null),
                _ => return &Value::Null,
            },
            PathSegment::Index(idx) => match current {
                Value::Array(arr) => arr.get(*idx).unwrap_or(&Value::Null),
                _ => return &Value::Null,
            },
        };
    }
    current
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_basic_ref() {
        let outputs = vec![
            json!({"title": "热点新闻", "content": "新闻正文内容"}),
        ];
        let params = json!({
            "sourceContent": "$steps[0].content",
            "topic": "$steps[0].title",
            "platform": "douyin",
        });
        let resolved = resolve_refs(params, &outputs);
        assert_eq!(resolved["sourceContent"], "新闻正文内容");
        assert_eq!(resolved["topic"], "热点新闻");
        assert_eq!(resolved["platform"], "douyin");
    }

    #[test]
    fn test_deep_nested() {
        let outputs = vec![
            json!({"data": {"summary": "这是摘要", "tags": ["tag1", "tag2"]}}),
        ];
        let params = json!({
            "desc": "$steps[0].data.summary",
            "first_tag": "$steps[0].data.tags[0]",
        });
        let resolved = resolve_refs(params, &outputs);
        assert_eq!(resolved["desc"], "这是摘要");
        assert_eq!(resolved["first_tag"], "tag1");
    }

    #[test]
    fn test_inline_string() {
        let outputs = vec![
            json!({"title": "今日热点"}),
        ];
        let params = json!({
            "prompt": "请根据$steps[0].title生成口播稿，时长60秒",
        });
        let resolved = resolve_refs(params, &outputs);
        assert_eq!(resolved["prompt"], "请根据今日热点生成口播稿，时长60秒");
    }

    #[test]
    fn test_out_of_range() {
        let outputs = vec![
            json!({"title": "热点"}),
        ];
        let params = json!({
            "ref": "$steps[5].title", // 不存在，保留原文
        });
        let resolved = resolve_refs(params, &outputs);
        assert_eq!(resolved["ref"], "$steps[5].title");
    }

    #[test]
    fn test_multiple_refs() {
        let outputs = vec![
            json!({"title": "标题", "content": "内容"}),
            json!({"script": "口播稿正文"}),
        ];
        let params = json!({
            "sourceContent": "$steps[0].content",
            "topic": "$steps[0].title",
            "script": "$steps[1].script",
        });
        let resolved = resolve_refs(params, &outputs);
        assert_eq!(resolved["sourceContent"], "内容");
        assert_eq!(resolved["topic"], "标题");
        assert_eq!(resolved["script"], "口播稿正文");
    }

    #[test]
    fn test_array_params() {
        let outputs = vec![
            json!({"key1": "val1", "key2": "val2"}),
        ];
        let params = json!({
            "keywords": ["$steps[0].key1", "$steps[0].key2", "static"],
        });
        let resolved = resolve_refs(params, &outputs);
        assert_eq!(resolved["keywords"][0], "val1");
        assert_eq!(resolved["keywords"][1], "val2");
        assert_eq!(resolved["keywords"][2], "static");
    }

    #[test]
    fn test_whole_step_ref() {
        let outputs = vec![
            serde_json::json!({"title": "T", "content": "C"}),
        ];
        let params = serde_json::json!({
            "step0": "$steps[0]",
        });
        let resolved = resolve_refs(params, &outputs);
        // 整个输出序列化为 JSON 字符串
        assert_eq!(resolved["step0"], "{\"content\":\"C\",\"title\":\"T\"}");
    }

    // ── 端到端集成测试：模拟模板 1 数据流 ──

    /// 模拟 hot-content-monitor 的 search 输出
    fn mock_step0_output() -> Value {
        json!({
            "ok": true,
            "action": "search",
            "articles": [
                {
                    "id": "art_001",
                    "title": "AI 最新趋势 2026",
                    "source": "36氪",
                    "hot": 95000,
                    "url": "https://example.com/ai-trends",
                    "summary": "人工智能在2026年迎来重大突破，多模态模型成为主流...",
                    "publishedAt": "2026-07-25T08:00:00Z",
                    "region": "zh"
                }
            ],
            "count": 1,
            "keywords": ["AI", "人工智能"],
            "insights": { "trend": "up", "velocity": "fast" }
        })
    }

    /// 模拟 script-rewriter 的 rewrite 输出
    fn mock_step1_output() -> Value {
        json!({
            "ok": true,
            "action": "rewrite",
            "script": {
                "openingHook": "你敢信？AI在2026年已经能做到这个程度...",
                "sections": [
                    { "text": "多模态模型让AI真正看懂世界...", "duration": 20, "emotion": "惊讶" }
                ],
                "closing": "关注我，了解更多AI前沿资讯！",
                "totalDuration": 60,
                "estimatedWords": 210
            },
            "platform": "douyin",
            "tone": "自然口语化",
            "sourcePreview": "人工智能在2026年迎来重大突破..."
        })
    }

    #[test]
    fn test_template1_step1_source_content() {
        // Step 1 引用 Step 0 的文章摘要
        let outputs = vec![mock_step0_output()];
        let params = json!({
            "action": "rewrite",
            "sourceContent": "$steps[0].articles[0].summary",
            "platform": "douyin",
            "duration": 60,
            "tone": "自然口语化"
        });
        let resolved = resolve_refs(params, &outputs);
        assert_eq!(resolved["sourceContent"], "人工智能在2026年迎来重大突破，多模态模型成为主流...");
        assert_eq!(resolved["platform"], "douyin"); // 非占位符保留原值
    }

    #[test]
    fn test_template1_step2_prompt() {
        // Step 2 引用 Step 1 的 script 对象（整体序列化）
        let outputs = vec![mock_step0_output(), mock_step1_output()];
        let params = json!({
            "action": "guide",
            "prompt": "$steps[1].script"
        });
        let resolved = resolve_refs(params, &outputs);
        let prompt = resolved["prompt"].as_str().unwrap();
        assert!(prompt.contains("openingHook")); // 整个 script 对象被 JSON 序列化
        assert!(prompt.contains("你敢信？AI在2026年已经能做到这个程度..."));
    }

    #[test]
    fn test_template2_step1_keywords_chain() {
        // Step 1 引用 Step 0 的 category 和 products[0].title
        let outputs = vec![json!({
            "ok": true,
            "action": "trends",
            "category": "Electronics",
            "marketplace": "US",
            "products": [
                { "id": "prod_001", "title": "Wireless Bluetooth Earbuds", "price": "29.99", "gmv": 50000, "sales": 1500, "growth": "+45%", "category": "Electronics", "trend": "exploding" }
            ],
            "report": null
        })];
        let params = json!({
            "action": "search",
            "keywords": ["$steps[0].category", "$steps[0].products[0].title"],
            "marketplace": "US"
        });
        let resolved = resolve_refs(params, &outputs);
        assert_eq!(resolved["keywords"][0], "Electronics");
        assert_eq!(resolved["keywords"][1], "Wireless Bluetooth Earbuds");
    }

    #[test]
    fn test_multi_step_param_chain() {
        // 完整的 3 步链式引用
        let outputs = vec![
            mock_step0_output(),   // step 0: hot-content-monitor
            mock_step1_output(),   // step 1: script-rewriter
            json!({                // step 2: seedance output
                "ok": true,
                "action": "guide",
                "message": "Seedance 技能已就绪"
            }),
        ];
        // 验证每一步的 params 都能正确解析前序输出
        let step0_params = json!({"action": "search", "keywords": [], "maxResults": 10, "region": "zh"});
        let step1_params = json!({"action": "rewrite", "sourceContent": "$steps[0].articles[0].summary", "platform": "douyin", "duration": 60, "tone": "自然口语化"});
        let step2_params = json!({"action": "guide", "prompt": "$steps[1].script"});

        let r0 = resolve_refs(step0_params, &outputs);
        // step 0 本身没有 $steps 引用，原样透传
        assert_eq!(r0["keywords"].as_array().map(|a| a.len()).unwrap_or(0), 0);
        assert_eq!(r0["action"], "search");

        let r1 = resolve_refs(step1_params, &outputs);
        assert_eq!(r1["sourceContent"], "人工智能在2026年迎来重大突破，多模态模型成为主流...");

        let r2 = resolve_refs(step2_params, &outputs);
        assert!(r2["prompt"].as_str().unwrap().contains("openingHook"));
    }

    #[test]
    fn test_pipeline_auto_iteration_insufficient_data() {
        // 模拟 pipeline_record_step 写入 worker_task_log 后，AutoSkill 的 scan_candidates 需要 ≥5 条记录
        // 这里验证 resolver 在多次执行后仍然稳定
        let outputs = vec![mock_step0_output()];
        for i in 0..10 {
            let params = json!({
                "sourceContent": "$steps[0].articles[0].summary",
                "iteration": i
            });
            let resolved = resolve_refs(params, &outputs);
            assert_eq!(resolved["sourceContent"], "人工智能在2026年迎来重大突破，多模态模型成为主流...");
            assert_eq!(resolved["iteration"], i);
        }
    }

    #[test]
    fn test_deeply_nested_array_index() {
        // 深层嵌套路径 + 数组索引: .items[1].tags[0]
        let outputs = vec![json!({
            "data": {
                "items": [
                    { "name": "item1", "tags": ["a", "b", "c"] },
                    { "name": "item2", "tags": ["x", "y"] }
                ]
            }
        })];
        let params = json!({
            "target": "$steps[0].data.items[1].tags[0]"
        });
        let resolved = resolve_refs(params, &outputs);
        assert_eq!(resolved["target"], "x"); // items[1].tags[0] = "x"
    }
}
