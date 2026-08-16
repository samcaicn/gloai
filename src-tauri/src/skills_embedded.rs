//! skills_embedded.rs — 编译期嵌入内置技能代码
//!
//! 技能 JS 代码通过 `include_str!` 在编译期嵌入 Rust 二进制，
//! 运行时通过 `get_builtin_skills()` 返回给前端，前端用
//! `new Function()` 在内存中编译执行。代码不经过 JS bundle，
//! 不在磁盘上落盘。
//!
//! Phase 2 新增: builtin skill coverage 收集。
//! `record_builtin_skill_run` 在每次 builtin 技能执行后记录覆盖率,
//! `get_builtin_skill_coverage` 返回各技能的执行统计。

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Mutex;

/// 单个 builtin 技能的覆盖率统计。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkillCoverage {
    pub skill_id: String,
    /// 各 action 的调用次数。
    pub action_counts: HashMap<String, u64>,
    /// 总调用次数。
    pub total_runs: u64,
    /// 入口 action 的调用次数。
    pub entry_action_runs: u64,
    /// 最后一次调用时间 (ISO 8601)。
    pub last_run_at: Option<String>,
    /// 最后一次调用传入的 action。
    pub last_action: Option<String>,
    /// 最后一次调用的结果状态: "ok" | "error" | "timeout"。
    pub last_status: Option<String>,
}

/// 全局覆盖率存储。key = skill_id。
static BUILTIN_COVERAGE: once_cell::sync::Lazy<Mutex<HashMap<String, SkillCoverage>>> =
    once_cell::sync::Lazy::new(|| Mutex::new(HashMap::new()));

/// 记录一次 builtin 技能执行。
pub fn record_builtin_skill_run(skill_id: &str, action: &str, status: &str) {
    let mut map = BUILTIN_COVERAGE.lock().unwrap();
    let entry = map.entry(skill_id.to_string()).or_insert_with(|| SkillCoverage {
        skill_id: skill_id.to_string(),
        action_counts: HashMap::new(),
        total_runs: 0,
        entry_action_runs: 0,
        last_run_at: None,
        last_action: None,
        last_status: None,
    });
    *entry.action_counts.entry(action.to_string()).or_insert(0) += 1;
    entry.total_runs += 1;
    // 检查是否为入口 action
    let skills = get_builtin_skills("http://127.0.0.1:8642");
    if let Some(skill) = skills.iter().find(|s| s.id == skill_id) {
        if skill.entry_action == action {
            entry.entry_action_runs += 1;
        }
    }
    entry.last_run_at = Some(chrono::Utc::now().to_rfc3339());
    entry.last_action = Some(action.to_string());
    entry.last_status = Some(status.to_string());
}

/// 获取所有 builtin 技能的覆盖率统计。
pub fn get_coverage_snapshot() -> Vec<SkillCoverage> {
    let map = BUILTIN_COVERAGE.lock().unwrap();
    // 也列出从未执行过的 builtin 技能
    let skills = get_builtin_skills("http://127.0.0.1:8642");
    let mut result: Vec<SkillCoverage> = map.values().cloned().collect();
    for skill in &skills {
        if !map.contains_key(&skill.id) {
            result.push(SkillCoverage {
                skill_id: skill.id.clone(),
                action_counts: HashMap::new(),
                total_runs: 0,
                entry_action_runs: 0,
                last_run_at: None,
                last_action: None,
                last_status: None,
            });
        }
    }
    result
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddedSkill {
    pub id: String,
    pub name: String,
    pub version: String,
    pub description: String,
    pub params: serde_json::Value,
    /// 代码中 __GW_URL__ 占位符在返回时替换为实际 gateway 地址
    pub code: String,
    /// 技能分类（用于前端分组展示）
    #[serde(default)]
    pub category: Option<String>,
    /// 标签列表（含 "platform" 时前端置顶展示）
    #[serde(default)]
    pub tags: Vec<String>,
    /// 技能"启动/执行"入口 action。前端 runBuiltinSkill 收到通用
    /// `{ action: 'execute' }` 时，若技能声明了 entry_action，会映射到
    /// 该值，让技能走正确的启动分支（各技能 action enum 不同：
    /// auto-product-comm=execute / trace-auto=start / publisher=monitor）。
    #[serde(default)]
    pub entry_action: String,
}

// ── 编译期嵌入能力层 + 技能 JS 代码 ─────────────────────
// include_str! 在编译期把文件内容读入 &str，
// 编译进 Rust 二进制，运行时不再依赖磁盘文件。

const CAPABILITIES_JS: &str = include_str!("skills/capabilities.js");
const SKILL_RUNTIME_JS: &str = include_str!("skills/skillRuntime.js");
const AUTO_PRODUCT_COMM_JS: &str = include_str!("skills/auto-product-comm.js");
const TRACE_AUTO_JS: &str = include_str!("skills/trace-auto.js");
const WECHAT_PUBLISHER_JS: &str = include_str!("skills/wechat-publisher.js");
const XIAOHONGSHU_PUBLISHER_JS: &str = include_str!("skills/xiaohongshu-publisher.js");
const SAFEOPC_SKILL_TESTER_JS: &str = include_str!("skills/safeopc-skill-tester.js");
const SEEDANCE_JS: &str = include_str!("skills/seedance.js");
const SEEDANCE_AD_CREATIVE_JS: &str = include_str!("skills/seedance-ad-creative.js");
// 跨境电商内置技能（10 个）
const AMAZON_PRODUCT_RESEARCH_JS: &str = include_str!("skills/amazon-product-research.js");
const ALIBABA_1688_SOURCING_JS: &str = include_str!("skills/alibaba-1688-sourcing.js");
const CROSS_BORDER_COMPETITOR_JS: &str = include_str!("skills/cross-border-competitor.js");
const CROSS_BORDER_EXPANSION_JS: &str = include_str!("skills/cross-border-expansion.js");
const GLOBAL_TAX_GUIDE_JS: &str = include_str!("skills/global-tax-guide.js");
const LISTING_OPTIMIZER_JS: &str = include_str!("skills/listing-optimizer.js");
const LISTING_TRANSLATOR_JS: &str = include_str!("skills/listing-translator.js");
const PROFIT_CALCULATOR_JS: &str = include_str!("skills/profit-calculator.js");
const SHOPIFY_OPERATOR_JS: &str = include_str!("skills/shopify-operator.js");
const TIKTOK_TREND_TRACKER_JS: &str = include_str!("skills/tiktok-trend-tracker.js");
// 热点内容→口播→视频技能（3 个）
const HOT_CONTENT_MONITOR_JS: &str = include_str!("skills/hot-content-monitor.js");
const SCRIPT_REWRITER_JS: &str = include_str!("skills/script-rewriter.js");
const CONTENT_TO_VIDEO_JS: &str = include_str!("skills/content-to-video.js");
// 微信小程序开发指导技能
const MINI_PROGRAM_HELPER_JS: &str = include_str!("skills/mini-program-helper.js");

/// 将能力层 + 运行时前置拼接到技能代码前
fn prepend_layers(skill_code: &str, gateway_url: &str) -> String {
    let cap = CAPABILITIES_JS.replace("__GW_URL__", gateway_url);
    let rt = SKILL_RUNTIME_JS.replace("__GW_URL__", gateway_url);
    let skill = skill_code.replace("__GW_URL__", gateway_url);
    format!("{}\n{}\n{}", cap, rt, skill)
}

/// 返回所有内置技能，gateway_url 会替换代码中的 __GW_URL__ 占位符
pub fn get_builtin_skills(gateway_url: &str) -> Vec<EmbeddedSkill> {
    vec![
        EmbeddedSkill {
            id: "builtin-auto-product-comm".to_string(),
            name: "自动选品智能沟通".to_string(),
            version: "3.0.0".to_string(),
            description: "LLM 驱动的多轮智能沟通引擎：CDP 控制浏览器打开微信小店选品中心，交互式预配置筛选条件，LLM 生成个性化开场白并多轮对话，自动发送产品资料，循环联系多家商家，支持人工干预，记录沟通效果，通过 Hermes 自我进化。".to_string(),
            category: Some("自动化".to_string()),
            tags: vec!["platform".to_string(), "automation".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "execute=执行自动沟通 | config=预配置 | status=查状态 | stop=停止 | evolve=自我进化 | logs=查看日志 | materials=管理资料",
                    "enum": ["execute", "config", "status", "stop", "evolve", "logs", "materials"]
                },
                "materialFolder": { "type": "string", "description": "[execute/materials] 产品资料文件夹路径" },
                "maxMerchants": { "type": "number", "description": "[execute] 最大商家数（默认 50）" },
                "maxConvRounds": { "type": "number", "description": "[execute] 单商家最大对话轮次（默认 5）" },
                "commStyle": { "type": "string", "description": "[config/execute] 沟通风格：friendly/professional/casual（默认 friendly）" },
                "enableHumanIntervention": { "type": "boolean", "description": "[execute] 启用人工干预（默认 true）" },
                "filters": { "type": "object", "description": "[config] 筛选条件 JSON" }
            }),
            entry_action: "execute".to_string(),
            code: prepend_layers(AUTO_PRODUCT_COMM_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-trace-auto".to_string(),
            name: "Trace Auto".to_string(),
            version: "4.0.0".to_string(),
            description: "Trae 自动化：自动循环(start) / 查状态(status) / 设置回复条件(chat) / 执行步骤(run_steps)".to_string(),
            category: Some("自动化".to_string()),
            tags: vec!["platform".to_string(), "automation".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "start=开始自动循环 | stop=停止 | status=查状态 | chat=设置回复条件 | run_steps=执行步骤",
                    "enum": ["start", "stop", "status", "chat", "run_steps"]
                },
                "goal": { "type": "string", "description": "[start] 任务目标" },
                "maxRounds": { "type": "number", "description": "[start] 最大轮次（默认 50）" },
                "idleTimeoutSec": { "type": "number", "description": "[start] 等待超时（默认 60s）" },
                "conditions": { "type": "array", "description": "[chat] 自动回复条件列表" },
                "steps": { "type": "array", "description": "[run_steps] 步骤列表" }
            }),
            entry_action: "start".to_string(),
            code: prepend_layers(TRACE_AUTO_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-wechat-publisher".to_string(),
            name: "公众号文章技能".to_string(),
            version: "3.0.0".to_string(),
            description: "纯 LLM 驱动的公众号文章撰写技能：7种写作框架、风格学习、质量检查、去AI化。无需浏览器，全程通过 LLM prompt 交互完成选题、写作、检查。".to_string(),
            category: Some("内容创作".to_string()),
            tags: vec!["platform".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "setup=配置公众号 | write=写文章 | monitor=热点选题写作 | publish=发布草稿 | auto=全自动 | status=查状态 | learn=风格学习 | check=质量检查 | deai=去AI化 | profile=查看配置 | upload=上传市场",
                    "enum": ["setup", "profile", "write", "monitor", "publish", "auto", "status", "learn", "check", "deai", "upload"]
                },
                "topic": { "type": "string", "description": "[write] 文章话题" },
                "content": { "type": "string", "description": "[check/deai] 文章内容" },
                "skipConfirm": { "type": "boolean", "description": "跳过确认步骤" }
            }),
            entry_action: "monitor".to_string(),
            code: prepend_layers(WECHAT_PUBLISHER_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-xiaohongshu-publisher".to_string(),
            name: "小红书文案技能".to_string(),
            version: "2.0.0".to_string(),
            description: "纯 LLM 驱动的小红书文案撰写技能：热点话题生成、爆款笔记撰写、配图描述、质量检查。无需浏览器，全程 LLM 交互。".to_string(),
            category: Some("内容创作".to_string()),
            tags: vec!["platform".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "monitor=热点选题写作 | write=直接写笔记 | check=质量检查 | status=查状态 | stop=停止",
                    "enum": ["monitor", "write", "check", "status", "stop"]
                },
                "brandKeywords": { "type": "string", "description": "自有业务品牌词，多个用逗号分隔" },
                "targetKeywords": { "type": "string", "description": "监测目标关键词，多个用逗号分隔" },
                "topic": { "type": "string", "description": "[write] 指定话题" },
                "content": { "type": "string", "description": "[check] 笔记内容" }
            }),
            entry_action: "monitor".to_string(),
            code: prepend_layers(XIAOHONGSHU_PUBLISHER_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-seedance".to_string(),
            name: "Seedance 视频生成".to_string(),
            version: "1.0.0".to_string(),
            description: "Seedance 2.0 AI 视频生成 — 文生视频、图生视频、参考视频生成，支持同步音频，最高 1080p，4-15 秒短片。纯 LLM prompt 驱动，引导用户完成视频创作。".to_string(),
            category: Some("内容创作".to_string()),
            tags: vec!["platform".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "generate=生成视频 | guide=创作指导 | help=帮助",
                    "enum": ["generate", "guide", "help"]
                },
                "prompt": { "type": "string", "description": "[generate] 视频描述提示词" },
                "model": { "type": "string", "description": "[generate] 模型: seedance-2-0 / seedance-2-0-fast / seedance-2-0-studio" }
            }),
            entry_action: "guide".to_string(),
            code: prepend_layers(SEEDANCE_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-seedance-ad-creative".to_string(),
            name: "Seedance 广告创意视频生成".to_string(),
            version: "1.0.0".to_string(),
            description: "基于 BytePlus Seedance 的广告创意视频生成——对标爆款短视频模板的镜头语言与爽点结构，将用户提供的静态商品图复刻生成具备原生网感、高点击潜力的商品展示视频。支持详细分析、预演模式、生成保护与风险兜底。".to_string(),
            category: Some("内容创作".to_string()),
            tags: vec!["platform".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "analyze=分析模板视频 | rewrite=生成改写方案 | preview=预演 | generate=正式生成",
                    "enum": ["analyze", "rewrite", "preview", "generate"]
                },
                "templateVideo": { "type": "string", "description": "[analyze] 模板视频路径或URL" },
                "productImage": { "type": "string", "description": "[analyze/rewrite] 商品图片路径" },
                "brief": { "type": "object", "description": "[rewrite/generate] 改写方案" }
            }),
            entry_action: "analyze".to_string(),
            code: prepend_layers(SEEDANCE_AD_CREATIVE_JS, gateway_url),
        },
        // ── 跨境电商内置技能 ──
        EmbeddedSkill {
            id: "builtin-amazon-product-research".to_string(),
            name: "亚马逊选品调研".to_string(),
            version: "1.0.0".to_string(),
            description: "Amazon 市场选品调研：关键词搜索、BSR 分析、评论分析、市场趋势洞察、竞品监控。支持多站点（US/UK/DE/FR/IT/ES/JP/CA）。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "amazon".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "search=商品搜索 | detail=商品详情 | keywords=关键词分析 | analyze=市场分析 | reviews=评论分析",
                    "enum": ["search", "detail", "keywords", "analyze", "reviews"]
                },
                "keywords": { "type": "array", "description": "搜索关键词列表" },
                "marketplace": { "type": "string", "description": "站点: US/UK/DE/FR/IT/ES/JP/CA" },
                "asins": { "type": "array", "description": "ASIN 列表" }
            }),
            entry_action: "search".to_string(),
            code: prepend_layers(AMAZON_PRODUCT_RESEARCH_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-alibaba-1688-sourcing".to_string(),
            name: "1688 跨境寻源".to_string(),
            version: "1.0.0".to_string(),
            description: "1688 跨境寻源与供应商调研：商品搜索、供应商评估、价格对比、起订量分析、跨境物流方案、同类品比价。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "1688".to_string(), "sourcing".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "search=搜索货源 | supplier=供应商分析 | compare=跨平台比价 | trending=热销榜单 | image_search=以图搜图",
                    "enum": ["search", "supplier", "compare", "trending", "image_search"]
                },
                "keywords": { "type": "array", "description": "[search] 搜索关键词", "items": { "type": "string" } },
                "maxResults": { "type": "number", "description": "[search] 最大结果数" },
                "filters": { "type": "object", "description": "[search] 筛选条件" },
                "supplierId": { "type": "string", "description": "[supplier] 供应商ID" },
                "productId": { "type": "string", "description": "[compare] 商品ID" },
                "targetMarket": { "type": "string", "description": "[compare] 目标市场" },
                "category": { "type": "string", "description": "[trending] 类目" }
            }),
            entry_action: "search".to_string(),
            code: prepend_layers(ALIBABA_1688_SOURCING_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-cross-border-competitor".to_string(),
            name: "跨境竞品分析".to_string(),
            version: "1.0.0".to_string(),
            description: "多平台竞品深度分析：竞品识别、定价策略、营销打法、Listing 质量评估、市场份额估算、差异化策略生成。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "competitor".to_string(), "analysis".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "search=竞品搜索 | analyze=深度分析 | monitor=价格监控 | compare=竞品对比 | landscape=市场格局",
                    "enum": ["search", "analyze", "monitor", "compare", "landscape"]
                },
                "keywords": { "type": "array", "description": "[search] 搜索关键词", "items": { "type": "string" } },
                "platforms": { "type": "array", "description": "[search] 平台: amazon/ebay/shopee", "items": { "type": "string" } },
                "asin": { "type": "string", "description": "[analyze] 商品 ASIN" },
                "asins": { "type": "array", "description": "[monitor/compare] ASIN 列表", "items": { "type": "string" } },
                "platform": { "type": "string", "description": "[analyze/monitor/compare] 平台 (默认amazon)" }
            }),
            entry_action: "search".to_string(),
            code: prepend_layers(CROSS_BORDER_COMPETITOR_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-cross-border-expansion".to_string(),
            name: "跨境市场拓展策略".to_string(),
            version: "1.0.0".to_string(),
            description: "全球市场拓展策略生成：市场评估、准入策略、本地化方案、物流布局、合规检查、ROI 预测。支持多国多平台。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "expansion".to_string(), "strategy".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "score=市场评分 | fulfillment=物流对比 | roadmap=路线图 | taxGuide=税务指南 | fullAnalysis=全链路分析",
                    "enum": ["score", "fulfillment", "roadmap", "taxGuide", "fullAnalysis"]
                },
                "targetMarkets": { "type": "array", "description": "[score/roadmap] 目标市场列表", "items": { "type": "string" } },
                "category": { "type": "string", "description": "[score] 产品品类" },
                "monthlyOrders": { "type": "number", "description": "[fulfillment] 月订单量 (默认100)" },
                "currentPlatform": { "type": "string", "description": "[roadmap] 当前平台 (默认amazon)" },
                "homeMarket": { "type": "string", "description": "[fullAnalysis] 起始市场 (默认US)" },
                "productInfo": { "type": "object", "description": "[fullAnalysis] 产品信息 { category, avgPrice, weight }" }
            }),
            entry_action: "score".to_string(),
            code: prepend_layers(CROSS_BORDER_EXPANSION_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-global-tax-guide".to_string(),
            name: "全球税务指南".to_string(),
            version: "1.0.0".to_string(),
            description: "跨境电商全球税务合规指南：各国 VAT/GST 税率查询、税务计算、申报指导、关税估算、合规检查清单。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "tax".to_string(), "compliance".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "check=税务检查 | landedCost=到岸成本 | compliance=产品合规",
                    "enum": ["check", "landedCost", "compliance"]
                },
                "markets": { "type": "array", "description": "[check/compliance] 市场列表", "items": { "type": "string" } },
                "revenue": { "type": "object", "description": "[check] 各市场年营收" },
                "productPrice": { "type": "number", "description": "[landedCost] 产品价格" },
                "origin": { "type": "string", "description": "[landedCost] 原产国 (默认CN)" },
                "destination": { "type": "string", "description": "[landedCost] 目的国" },
                "productType": { "type": "string", "description": "[compliance] 产品类型" }
            }),
            entry_action: "check".to_string(),
            code: prepend_layers(GLOBAL_TAX_GUIDE_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-listing-optimizer".to_string(),
            name: "Listing 优化器".to_string(),
            version: "1.0.0".to_string(),
            description: "AI 驱动的 Listing 全链路优化：标题优化、五点描述优化、A+ 内容生成、关键词埋词、图片策略、转化率提升建议。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "listing".to_string(), "optimization".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "title=标题优化 | bullets=五点优化 | description=描述生成 | searchTerms=搜索词优化 | fullOptimize=完整优化",
                    "enum": ["title", "bullets", "description", "searchTerms", "fullOptimize"]
                },
                "productInfo": { "type": "object", "description": "[title/bullets/description] 产品信息" },
                "marketplace": { "type": "string", "description": "站点 (默认US)" },
                "currentBullets": { "type": "array", "description": "[bullets] 当前五点描述", "items": { "type": "string" } },
                "style": { "type": "string", "description": "[description] 风格" },
                "keywords": { "type": "array", "description": "[searchTerms] 核心关键词", "items": { "type": "string" } },
                "listing": { "type": "object", "description": "[fullOptimize] 完整Listing" }
            }),
            entry_action: "fullOptimize".to_string(),
            code: prepend_layers(LISTING_OPTIMIZER_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-listing-translator".to_string(),
            name: "Listing 多语言翻译".to_string(),
            version: "1.0.0".to_string(),
            description: "专业级 Listing 多语言翻译与本地化：支持 20+ 语言、本地化表达适配、关键词本地化、文化适配检查、SEO 友好翻译。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "translation".to_string(), "localization".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "translate=Listing翻译 | keywords=关键词本地化",
                    "enum": ["translate", "keywords"]
                },
                "listing": { "type": "object", "description": "[translate] Listing { title, bullets[], description, keywords[] }" },
                "targetLanguages": { "type": "array", "description": "[translate] 目标语言", "items": { "type": "string" } },
                "keywords": { "type": "array", "description": "[keywords] 种子关键词", "items": { "type": "string" } },
                "targetMarket": { "type": "string", "description": "[keywords] 目标市场" }
            }),
            entry_action: "translate".to_string(),
            code: prepend_layers(LISTING_TRANSLATOR_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-profit-calculator".to_string(),
            name: "利润计算器".to_string(),
            version: "1.0.0".to_string(),
            description: "跨境电商利润计算器：成本核算、费用计算、净利润分析、定价建议、盈亏平衡分析、多平台费用对比。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "profit".to_string(), "calculator".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "analyze=利润分析 | suggest=定价建议 | fba=FBA费用计算",
                    "enum": ["analyze", "suggest", "fba"]
                },
                "productInfo": { "type": "object", "description": "[analyze/fba] 产品信息 { purchasePrice, sellingPrice, platform, weight, category }" },
                "market": { "type": "string", "description": "[analyze/fba] 市场: US/UK/DE (默认US)" },
                "costInfo": { "type": "object", "description": "[suggest] 成本信息" },
                "targetMargin": { "type": "number", "description": "[suggest] 目标利润率% (默认30)" }
            }),
            entry_action: "analyze".to_string(),
            code: prepend_layers(PROFIT_CALCULATOR_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-shopify-operator".to_string(),
            name: "Shopify 运营助手".to_string(),
            version: "1.0.0".to_string(),
            description: "Shopify 店铺运营全流程助手：商品上架优化、主题定制、营销活动配置、SEO 优化、订单管理、数据分析。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "shopify".to_string(), "operations".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "audit=店铺审计 | optimize=商品优化 | recovery=弃购挽回 | expand=市场扩张",
                    "enum": ["audit", "optimize", "recovery", "expand"]
                },
                "storeUrl": { "type": "string", "description": "[audit] 店铺URL" },
                "metrics": { "type": "array", "description": "[audit] 审计维度", "items": { "type": "string" } },
                "products": { "type": "array", "description": "[optimize] 商品列表", "items": { "type": "object" } },
                "abandonedRate": { "type": "number", "description": "[recovery] 弃购率%" },
                "avgOrderValue": { "type": "number", "description": "[recovery] 平均客单价" },
                "targetMarkets": { "type": "array", "description": "[expand] 目标市场", "items": { "type": "string" } },
                "currentCurrency": { "type": "string", "description": "[expand] 当前币种" }
            }),
            entry_action: "audit".to_string(),
            code: prepend_layers(SHOPIFY_OPERATOR_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-tiktok-trend-tracker".to_string(),
            name: "TikTok 趋势追踪器".to_string(),
            version: "1.0.0".to_string(),
            description: "TikTok 电商趋势追踪：热品发现、趋势分析、竞品监控、达人挖掘、内容策略生成、数据报告。".to_string(),
            category: Some("跨境电商".to_string()),
            tags: vec!["cross-border".to_string(), "tiktok".to_string(), "trends".to_string(), "ecommerce".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "search=搜索热品 | videos=带货视频 | trending=趋势报告 | creator=达人分析",
                    "enum": ["search", "videos", "trending", "creator"]
                },
                "keywords": { "type": "array", "description": "[search] 搜索关键词", "items": { "type": "string" } },
                "marketplace": { "type": "string", "description": "站点: US/UK/ID/TH/VN (默认US)" },
                "maxResults": { "type": "number", "description": "[search] 最大结果数" },
                "productId": { "type": "string", "description": "[videos] 商品ID" },
                "category": { "type": "string", "description": "[trending] 类目" },
                "creatorId": { "type": "string", "description": "[creator] 达人ID" }
            }),
            entry_action: "trending".to_string(),
            code: prepend_layers(TIKTOK_TREND_TRACKER_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-safeopc-skill-tester".to_string(),
            name: "技能自动测试器".to_string(),
            version: "1.0.0".to_string(),
            description: "自动发现所有内置技能，逐一执行安全测试动作（status），监测执行效果，生成测试报告。用于快速验证所有技能是否正常工作。".to_string(),
            category: Some("开发工具".to_string()),
            tags: vec!["platform".to_string(), "testing".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "run=自动测试所有技能 | status=查看上次测试结果 | report=生成LLM测试报告",
                    "enum": ["run", "status", "report"]
                }
            }),
            entry_action: "run".to_string(),
            code: prepend_layers(SAFEOPC_SKILL_TESTER_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-hot-content-monitor".to_string(),
            name: "热点内容监测".to_string(),
            version: "1.0.0".to_string(),
            description: "多平台热点内容监测与搜索：根据业务关键词搜索热点文章、监测话题趋势、提取文章内容，支持中英文多源聚合。集成百度热点/微博热搜/抖音热榜/知乎热榜等来源模拟。".to_string(),
            category: Some("内容创作".to_string()),
            tags: vec!["content".to_string(), "monitor".to_string(), "trending".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "search=搜索热点 | monitor=监测话题 | trending=趋势报告 | extract=提取文章",
                    "enum": ["search", "monitor", "trending", "extract"]
                },
                "keywords": { "type": "array", "description": "[search/monitor] 搜索关键词列表" },
                "brandKeywords": { "type": "string", "description": "[monitor] 自有品牌词" },
                "targetKeywords": { "type": "string", "description": "[monitor] 监测目标关键词" },
                "region": { "type": "string", "description": "zh/cn/en 等" },
                "maxResults": { "type": "number", "description": "最大结果数" },
                "url": { "type": "string", "description": "[extract] 文章 URL" }
            }),
            entry_action: "search".to_string(),
            code: prepend_layers(HOT_CONTENT_MONITOR_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-script-rewriter".to_string(),
            name: "口播文案改写".to_string(),
            version: "1.0.0".to_string(),
            description: "专业口播文案改写与生成引擎：支持热点文章改写为口播脚本、从零生成口播稿、文案优化润色、爆款风格学习。适配抖音/快手/视频号/小红书等多平台。".to_string(),
            category: Some("内容创作".to_string()),
            tags: vec!["script".to_string(), "rewrite".to_string(), "copywriting".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "rewrite=改写文案 | generate=生成口播稿 | optimize=优化润色 | style=风格学习",
                    "enum": ["rewrite", "generate", "optimize", "style"]
                },
                "sourceContent": { "type": "string", "description": "[rewrite] 源文章内容" },
                "topic": { "type": "string", "description": "[generate] 话题/主题" },
                "platform": { "type": "string", "description": "目标平台: douyin/kuaishou/xhs/video号" },
                "duration": { "type": "number", "description": "口播时长(秒)" },
                "tone": { "type": "string", "description": "风格: 自然口语化/专业/幽默/种草" },
                "script": { "type": "string", "description": "[optimize] 待优化文案" }
            }),
            entry_action: "rewrite".to_string(),
            code: prepend_layers(SCRIPT_REWRITER_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-content-to-video".to_string(),
            name: "热点→口播→视频 Pipeline".to_string(),
            version: "1.0.0".to_string(),
            description: "全自动内容到视频生成管道：①根据业务关键词搜索热点文章 ②AI自动改写为口播文案 ③调用 builtin-seedance 视频生成技能生成短视频。支持全流程或分步执行。".to_string(),
            category: Some("内容创作".to_string()),
            tags: vec!["pipeline".to_string(), "video".to_string(), "content".to_string(), "automation".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "pipeline=全流程 | hotToScript=热点→文案 | scriptToVideo=文案→视频 | direct=直接生成视频",
                    "enum": ["pipeline", "hotToScript", "scriptToVideo", "direct"]
                },
                "keywords": { "type": "array", "description": "[pipeline/hotToScript] 搜索关键词" },
                "articleInput": { "type": "string", "description": "[hotToScript] 直接输入文章内容" },
                "script": { "type": "string", "description": "[scriptToVideo] 口播文案" },
                "topic": { "type": "string", "description": "[direct] 视频主题" },
                "platform": { "type": "string", "description": "目标平台" },
                "duration": { "type": "number", "description": "视频时长(秒)" },
                "tone": { "type": "string", "description": "口播风格" },
                "model": { "type": "string", "description": "视频模型: seedance-2-0-fast 等" }
            }),
            entry_action: "pipeline".to_string(),
            code: prepend_layers(CONTENT_TO_VIDEO_JS, gateway_url),
        },
        EmbeddedSkill {
            id: "builtin-mini-program-helper".to_string(),
            name: "微信小程序开发助手".to_string(),
            version: "1.0.0".to_string(),
            description: "全流程微信小程序开发指导：项目搭建、WXML/WXSS模板、JavaScript逻辑、API使用、云开发、UI组件库、发布审核流程、性能优化、问题排查、推广营销。LLM驱动交互式问答，覆盖从零基础到上线的完整开发周期。".to_string(),
            category: Some("开发工具".to_string()),
            tags: vec!["platform".to_string(), "mini-program".to_string(), "wechat".to_string(), "development".to_string(), "guidance".to_string()],
            params: serde_json::json!({
                "action": {
                    "type": "string",
                    "description": "create=项目搭建 | guidance=开发指导 | template=代码模板 | publish=发布流程 | optimize=性能优化 | troubleshoot=问题排查 | query=知识查询",
                    "enum": ["create", "guidance", "template", "publish", "optimize", "troubleshoot", "query"]
                },
                "projectName": { "type": "string", "description": "[create] 项目名称" },
                "appId": { "type": "string", "description": "[create] 小程序 AppID" },
                "description": { "type": "string", "description": "[create] 项目描述" },
                "template": { "type": "string", "description": "[create] 框架偏好: 原生/uniapp/taro" },
                "topic": { "type": "string", "description": "[guidance] 咨询主题" },
                "question": { "type": "string", "description": "[guidance/troubleshoot] 具体问题" },
                "experience": { "type": "string", "description": "[guidance] 经验水平: 新手/进阶/专家" },
                "pageType": { "type": "string", "description": "[template] 页面类型: list/form/detail/tabs/login" },
                "feature": { "type": "string", "description": "[template] 功能描述（LLM生成模板）" },
                "stage": { "type": "string", "description": "[publish] 当前阶段: 准备/待审核/被驳回/已发布" },
                "focus": { "type": "string", "description": "[optimize] 优化重点: 首屏/分包/渲染/启动" },
                "issue": { "type": "string", "description": "[troubleshoot] 问题描述" },
                "errorCode": { "type": "string", "description": "[troubleshoot] 错误码" },
                "query": { "type": "string", "description": "[query] 知识查询关键词" }
            }),
            entry_action: "guidance".to_string(),
            code: prepend_layers(MINI_PROGRAM_HELPER_JS, gateway_url),
        },
    ]
}

// ── Tauri IPC command ────────────────────────────────────
// 前端通过 invoke('get_builtin_skills') 获取内置技能列表，
// 技能代码在 Rust 二进制中编译，运行时通过 IPC 传给前端。

/// 获取所有内置技能（代码已编译进 exe）
#[tauri::command]
pub fn get_builtin_skills_command() -> Result<Vec<EmbeddedSkill>, String> {
    let gw = "http://127.0.0.1:8642";
    log::info!("[ipc] get_builtin_skills_command -> {} skills", get_builtin_skills(gw).len());
    Ok(get_builtin_skills(gw))
}

/// 记录一次 builtin 技能执行（由前端 runBuiltinSkill 在执行后调用）。
#[tauri::command]
pub fn record_builtin_skill_run_command(
    skill_id: String,
    action: String,
    status: String,
) -> Result<(), String> {
    record_builtin_skill_run(&skill_id, &action, &status);
    Ok(())
}

/// 获取所有 builtin 技能的覆盖率统计。
#[tauri::command]
pub fn get_builtin_skill_coverage_command() -> Result<Vec<SkillCoverage>, String> {
    Ok(get_coverage_snapshot())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mini_program_helper_skill_registered() {
        let skills = get_builtin_skills("http://127.0.0.1:8642");
        let skill = skills.iter().find(|s| s.id == "builtin-mini-program-helper");
        assert!(skill.is_some(), "mini-program-helper skill not found");
        let s = skill.unwrap();
        assert_eq!(s.name, "微信小程序开发助手");
        assert_eq!(s.version, "1.0.0");
        assert_eq!(s.entry_action, "guidance");
        assert_eq!(s.category.as_deref(), Some("开发工具"));
        assert!(!s.code.is_empty());
        assert!(s.code.contains("devGuidance"), "code missing devGuidance");
        assert!(s.code.contains("codeTemplate"), "code missing codeTemplate");

        let params = s.params.as_object().unwrap();
        let action = params.get("action").unwrap().as_object().unwrap();
        let enum_vals = action.get("enum").unwrap().as_array().unwrap();
        let actions: Vec<&str> = enum_vals.iter().map(|v| v.as_str().unwrap()).collect();
        for a in &["create", "guidance", "template", "publish", "optimize", "troubleshoot", "query"] {
            assert!(actions.contains(a), "missing action: {}", a);
        }

        assert!(s.tags.contains(&"mini-program".to_string()));
        assert!(s.tags.contains(&"development".to_string()));
    }

    #[test]
    fn test_all_builtin_skills_have_unique_ids() {
        let skills = get_builtin_skills("http://127.0.0.1:8642");
        let mut ids: Vec<&str> = skills.iter().map(|s| s.id.as_str()).collect();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), skills.len(), "duplicate skill IDs found");
    }

    #[test]
    fn test_all_builtin_skills_have_nonempty_code() {
        let skills = get_builtin_skills("http://127.0.0.1:8642");
        for s in &skills {
            assert!(!s.code.is_empty(), "empty code for skill: {}", s.id);
        }
    }
}
