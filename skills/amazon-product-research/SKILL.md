---
id: "com.tupautochrome.skills.amazon-product-research"
name: "亚马逊选品调研"
name_en: "Amazon Product Research"
version: "1.0.0"
author: "tupAI"
license: "MIT"
icon: ""

category: "data"
software_names: ["亚马逊", "Amazon"]
software_names_en: ["Amazon"]
tags: ["amazon", "product-research", "cross-border", "ecommerce", "data-analysis", "keyword", "bsr", "market-analysis"]
keywords: ["亚马逊", "选品", "调研", "BSR", "关键词", "市场分析", "竞品", "Amazon", "product research", "市场调研"]

capabilities:
  - id: "product_search"
    name: "亚马逊商品搜索"
    description: "通过关键词在亚马逊搜索商品，获取排名、价格、评分等数据"
    inputs:
      - { name: "keywords", type: "array", items: "string", required: true, description: "搜索关键词列表" }
      - { name: "marketplace", type: "string", default: "US", description: "站点: US/UK/DE/FR/IT/ES/JP/CA" }
      - { name: "maxResults", type: "number", default: 20, description: "最大结果数" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "products", type: "array", description: "商品列表" }
      - { name: "marketInsights", type: "object", description: "市场洞察" }

  - id: "product_detail"
    name: "亚马逊商品详情"
    description: "通过ASIN获取亚马逊商品完整详情、BSR、评论分析"
    inputs:
      - { name: "asins", type: "array", items: "string", required: true, description: "ASIN 列表" }
      - { name: "marketplace", type: "string", default: "US", description: "站点" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "details", type: "array", description: "商品详情" }

  - id: "keyword_analysis"
    name: "关键词分析"
    description: "分析搜索词频、长尾词、ABA数据趋势"
    inputs:
      - { name: "keyword", type: "string", required: true, description: "核心关键词" }
      - { name: "marketplace", type: "string", default: "US" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "relatedKeywords", type: "array", description: "相关关键词" }
      - { name: "trend", type: "object", description: "搜索趋势" }

runtime:
  engine: "js"
  engineVersion: ">=1.0.0 <2.0.0"
  caps:
    - "cap.llm@^1.0.0"
    - "cap.storage@^1.0.0"
  permissions:
    - "http:fetch:*"
    - "storage:readwrite"

distribution:
  channel: "stable"
  minAppVersion: "0.5.0"
  maxAppVersion: "2.0.0"
  rollout:
    percentage: 100
    targetUsers: []

signing:
  algorithm: "ed25519"
  publicKey: ""
---

# 亚马逊选品调研 v1.0

> 基于 LLM + 公开数据源的亚马逊选品调研工具。支持关键词搜索、ASIN 详情分析、BSR 排名追踪、评论情感分析和市场洞察。

## 核心能力

- **商品搜索**: 按关键词搜索亚马逊商品，获取实时排名、价格、评分、评论数
- **商品详情**: 通过 ASIN 获取完整商品信息，包括 BSR、FBA 费用、尺寸重量
- **关键词分析**: 分析搜索趋势、相关关键词、ABA 数据
- **市场洞察**: 自动生成市场容量、竞争度、利润空间分析报告
- **评论分析**: 智能分析评论情感、痛点提取、评分分布

## 使用示例

```json
// 1. 关键词搜索选品
{ "action": "search", "keywords": ["yoga mat", "exercise mat"], "marketplace": "US", "maxResults": 30 }

// 2. 获取 ASIN 详情
{ "action": "detail", "asins": ["B08N6LT3VC"], "marketplace": "US" }

// 3. 市场分析报告
{ "action": "analyze", "keywords": ["yoga mat"], "marketplace": "US" }

// 4. 关键词研究
{ "action": "keywords", "keyword": "yoga mat", "marketplace": "US" }

// 5. 评论分析
{ "action": "reviews", "asin": "B08N6LT3VC", "maxReviews": 100 }
```
