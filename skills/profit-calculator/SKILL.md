---
id: "com.tupautochrome.skills.profit-calculator"
name: "跨境利润计算器"
name_en: "Cross-Border Profit Calculator"
version: "1.0.0"
author: "tupAI"
license: "MIT"
icon: ""

category: "data"
software_names: ["亚马逊", "Amazon", "eBay", "Shopify"]
software_names_en: ["Amazon", "eBay", "Shopify"]
tags: ["profit", "calculator", "margin", "cross-border", "ecommerce", "fba", "pricing", "financial"]
keywords: ["利润", "计算", "毛利率", "FBA费用", "定价", "成本核算", "ROI"]

capabilities:
  - id: "profit_analyze"
    name: "利润分析"
    description: "计算跨境电商商品利润和毛利率"
    inputs:
      - { name: "productInfo", type: "object", required: true, description: "{ purchasePrice, sellingPrice, platform, weight, category }" }
      - { name: "marketplace", type: "string", default: "amazon" }
      - { name: "market", type: "string", default: "US" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "profit", type: "object" }

  - id: "price_suggest"
    name: "定价建议"
    description: "基于成本和目标利润率推荐售价"
    inputs:
      - { name: "costInfo", type: "object", required: true }
      - { name: "targetMargin", type: "number", default: 30 }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "suggestions", type: "object" }

  - id: "fba_calc"
    name: "FBA费用计算"
    description: "计算亚马逊FBA各项费用"
    inputs:
      - { name: "productInfo", type: "object", required: true }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "fees", type: "object" }

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
---

# 跨境利润计算器 v1.0

> 跨境电商全链路利润计算工具。支持 Amazon FBA 费用计算、多平台佣金核算、跨境物流成本、关税/VAT 影响分析、目标利润定价建议。

## 核心能力

- **利润分析**: 采购成本 + 物流 + 关税 + 平台佣金 + FBA费用 → 净利润
- **FBA费用计算**: 仓储费、配送费、长期仓储费、退货处理费
- **定价建议**: 基于目标利润率推荐售价，含汇率换算
- **多平台对比**: Amazon / eBay / Shopify / TikTok Shop 佣金差异

## 使用示例

```json
// 1. 利润分析
{ "action": "analyze", "productInfo": { "purchasePrice": 3.5, "sellingPrice": 19.99, "platform": "amazon", "weight": 0.3, "category": "electronics" }, "market": "US" }

// 2. 定价建议
{ "action": "suggest", "costInfo": { "purchasePrice": 5, "shipping": 3.5, "fbaFee": 4.5, "adRate": 0.15 }, "targetMargin": 30 }

// 3. FBA费用计算
{ "action": "fba", "productInfo": { "weight": 0.5, "dimensions": "10x8x2", "category": "home" }, "market": "US" }
```
