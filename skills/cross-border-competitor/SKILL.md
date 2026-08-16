---
id: "com.tupautochrome.skills.cross-border-competitor"
name: "跨境竞品分析"
name_en: "Cross-border Competitor Analysis"
version: "1.0.0"
author: "AIMarketing"
license: "MIT"
icon: ""

category: "data"
software_names: ["亚马逊", "Amazon", "eBay", "Shopee"]
software_names_en: ["Amazon", "eBay", "Shopee"]
tags: ["competitor", "analysis", "cross-border", "ecommerce", "amazon", "ebay", "shopee", "benchmarking"]
keywords: ["竞品", "分析", "竞争", "benchmark", "competitor", "market-research", "跨境电商分析"]

capabilities:
  - id: "competitor_search"
    name: "竞品搜索"
    description: "搜索并识别主要竞品，支持多平台"
    inputs:
      - { name: "keywords", type: "array", items: "string", required: true }
      - { name: "platforms", type: "array", items: "string", default: ["amazon"], description: "amazon/ebay/shopee" }
      - { name: "maxResults", type: "number", default: 20 }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "competitors", type: "array" }

  - id: "competitor_detail"
    name: "竞品深度分析"
    description: "分析指定竞品的定价策略、流量结构、运营手法"
    inputs:
      - { name: "asin", type: "string" }
      - { name: "platform", type: "string", default: "amazon" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "analysis", type: "object" }

  - id: "price_monitor"
    name: "价格监控"
    description: "监控竞品价格变化和促销活动"
    inputs:
      - { name: "asins", type: "array", items: "string", required: true }
      - { name: "platform", type: "string", default: "amazon" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "monitoring", type: "object" }

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

# 跨境竞品分析 v1.0

> 多平台、多维度竞品分析工具，支持 Amazon/eBay/Shopee 主流跨境电商平台的竞品识别、定价分析、流量拆解、运营手法挖掘。

## 核心能力

- **竞品发现**: 按关键词识别各平台主要竞品及市场格局
- **深度分析**: 定价策略、Review 分析、流量结构、关键词布局
- **价格监控**: 追踪竞品价格变动、促销节奏、Coupon 策略
- **运营手法**: 分析竞品 Listing 优化、广告策略、站外推广
- **差距分析**: 自动生成 SWOT 和 actionable 改进建议

## 使用示例

```json
// 1. 搜索竞品
{ "action": "search", "keywords": ["wireless earbuds", "bluetooth earphones"], "platforms": ["amazon", "ebay"] }

// 2. 深度分析竞品
{ "action": "analyze", "asin": "B08N6LT3VC", "platform": "amazon" }

// 3. 价格监控
{ "action": "monitor", "asins": ["B08N6LT3VC", "B09G9HRM4H"], "platform": "amazon" }

// 4. 多竞品对比报告
{ "action": "compare", "asins": ["B08N6LT3VC", "B09G9HRM4H", "B0B5H8K5L"], "platform": "amazon" }

// 5. 市场格局分析
{ "action": "landscape", "keywords": ["wireless earbuds"], "platform": "amazon" }
```
