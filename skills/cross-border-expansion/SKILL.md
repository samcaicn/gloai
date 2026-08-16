---
id: "com.tupautochrome.skills.cross-border-expansion"
name: "跨境市场扩张战略"
name_en: "Cross-Border Expansion Strategy"
version: "1.0.0"
author: "tupAI"
license: "MIT"
icon: ""

category: "data"
software_names: ["亚马逊", "Amazon", "Shopify", "eBay", "Walmart"]
software_names_en: ["Amazon", "Shopify", "eBay", "Walmart"]
tags: ["cross-border", "expansion", "market-entry", "strategy", "fulfillment", "tax", "localization", "international"]
keywords: ["跨境", "市场扩张", "国际化", "市场评分", "物流", "税务合规", "本土化"]

capabilities:
  - id: "market_scoring"
    name: "目标市场评分"
    description: "基于8维度加权评分系统评估目标市场"
    inputs:
      - { name: "targetMarkets", type: "array", items: "string", required: true, description: "目标市场列表 US/UK/DE/JP/CA/AU..." }
      - { name: "category", type: "string", description: "产品品类" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "rankings", type: "array", description: "市场排名" }

  - id: "fulfillment_compare"
    name: "物流方案对比"
    description: "比较直邮/3PL/FBA/Dropship/跨境仓5种物流模式"
    inputs:
      - { name: "markets", type: "array", items: "string" }
      - { name: "monthlyOrders", type: "number", default: 100 }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "recommendations", type: "object" }

  - id: "expansion_roadmap"
    name: "扩张路线图"
    description: "生成阶段式市场扩张路线图"
    inputs:
      - { name: "currentPlatform", type: "string", default: "amazon" }
      - { name: "homeMarket", type: "string", default: "US" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "roadmap", type: "object" }

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

# 跨境市场扩张战略 v1.0

> 基于 nexscope-ai/ecommerce-skills (59.2k 安装) 的市场扩张战略顾问。8 维度加权评分 15+ 国际市场，对比 5 种物流方案，提供 VAT/GST 合规指南和分阶段扩张路线图。

## 核心能力

- **市场评分矩阵**: 8 维度（市场规模/电商渗透率/竞争强度/法规复杂度/物流/支付/文化距离/IP保护）加权评分
- **物流方案对比**: 直邮 / 本地 3PL / FBA / Dropship / 跨境仓 5 种模式成本和时效对比
- **税务合规**: EU VAT/IOSS、UK VAT、US Sales Tax、CA GST、AU GST、JP 消费税
- **支付生态**: 各国本地支付方式偏好和支付网关推荐
- **扩张路线图**: 分阶段路线图，含里程碑和 KPI

## 使用示例

```json
// 1. 市场评分
{ "action": "score", "targetMarkets": ["UK", "DE", "JP", "CA", "AU"], "category": "electronics" }

// 2. 物流方案对比
{ "action": "fulfillment", "markets": ["UK", "DE"], "monthlyOrders": 200 }

// 3. 生成扩张路线图
{ "action": "roadmap", "currentPlatform": "amazon", "homeMarket": "US" }

// 4. 税务合规指南
{ "action": "taxGuide", "markets": ["UK", "DE", "JP"] }

// 5. 全链路分析
{ "action": "fullAnalysis", "productInfo": { "category": "electronics", "avgPrice": 49.99, "weight": "0.5kg" }, "homeMarket": "US", "targetMarkets": ["UK", "DE", "CA"] }
```
