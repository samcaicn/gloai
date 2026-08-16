---
id: "com.tupautochrome.skills.global-tax-guide"
name: "全球税务合规指南"
name_en: "Global Tax Compliance Guide"
version: "1.0.0"
author: "tupAI"
license: "MIT"
icon: ""

category: "data"
software_names: ["亚马逊", "Amazon", "Shopify", "eBay"]
software_names_en: ["Amazon", "Shopify", "eBay"]
tags: ["tax", "vat", "gst", "compliance", "cross-border", "ecommerce", "ioss", "customs"]
keywords: ["税务", "VAT", "GST", "合规", "关税", "海关", "IOSS", "销售税", "跨境税务"]

capabilities:
  - id: "tax_check"
    name: "税务合规检查"
    description: "检查多国家VAT/GST/销售税合规要求"
    inputs:
      - { name: "markets", type: "array", items: "string", required: true }
      - { name: "revenue", type: "object", description: "{ market: annualRevenue }" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "obligations", type: "array" }

  - id: "landed_cost"
    name: "到岸成本计算"
    description: "计算跨境到岸成本和利润影响"
    inputs:
      - { name: "productPrice", type: "number", required: true }
      - { name: "origin", type: "string", default: "CN" }
      - { name: "destination", type: "string", required: true }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "costBreakdown", type: "object" }

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

# 全球税务合规指南 v1.0

> 跨境电商多国税务合规专家。覆盖 EU VAT/IOSS、UK VAT、US Sales Tax、JP Consumption Tax、AU GST、CA GST 等主要市场，提供到岸成本计算和合规路线图。

## 核心能力

- **税务合规检查**: 识别各市场 VAT/GST/销售税注册义务和申报要求
- **到岸成本计算**: 计算关税、增值税、物流、保险等全链路成本
- **产品合规筛查**: CE/FCC/FDA/EPR/WEEE 等认证要求检查
- **税务策略优化**: IOSS/OSS 一站式申报方案、递延纳税策略

## 使用示例

```json
// 1. 税务合规检查
{ "action": "check", "markets": ["UK", "DE", "FR", "JP"], "revenue": { "UK": 120000, "DE": 80000 } }

// 2. 到岸成本计算
{ "action": "landedCost", "productPrice": 12.99, "origin": "CN", "destination": "DE" }

// 3. 产品合规检查
{ "action": "compliance", "markets": ["EU", "US", "JP"], "productType": "electronics" }
```
