---
id: "com.tupautochrome.skills.shopify-operator"
name: "Shopify店铺运营"
name_en: "Shopify Store Operator"
version: "1.0.0"
author: "tupAI"
license: "MIT"
icon: ""

category: "data"
software_names: ["Shopify"]
software_names_en: ["Shopify"]
tags: ["shopify", "store", "ecommerce", "d2c", "dropshipping", "inventory", "orders", "catalog"]
keywords: ["Shopify", "店铺管理", "商品管理", "订单处理", "库存", "D2C", "独立站"]

capabilities:
  - id: "store_audit"
    name: "店铺审计"
    description: "审计Shopify店铺数据质量和运营健康度"
    inputs:
      - { name: "storeUrl", type: "string" }
      - { name: "metrics", type: "array", items: "string", default: ["catalog", "seo", "speed", "conversion"] }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "audit", type: "object" }

  - id: "product_optimize"
    name: "商品优化"
    description: "优化商品标题、描述、SEO标签"
    inputs:
      - { name: "products", type: "array", required: true }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "optimized", type: "array" }

  - id: "abandoned_recovery"
    name: "弃购挽回"
    description: "生成弃购挽回策略和邮件模板"
    inputs:
      - { name: "abandonedRate", type: "number", default: 75 }
      - { name: "avgOrderValue", type: "number", default: 45 }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "strategy", type: "object" }

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

# Shopify店铺运营 v1.0

> Shopify 独立站全链路运营助手。支持店铺审计、商品 SEO 优化、弃购挽回、多货币/多语言配置、跨境物流方案、转化率优化。

## 核心能力

- **店铺审计**: 检查网站速度、SEO、商品数据质量、转化漏斗健康度
- **商品优化**: 批量优化商品标题、描述、图片 Alt 标签和 SEO
- **弃购挽回**: 智能弃购分析 + 邮件/SMS 挽回策略
- **多市场配置**: Shopify Markets 多币种/多语言/关税设置指南

## 使用示例

```json
// 1. 店铺健康审计
{ "action": "audit", "storeUrl": "mystore.com", "metrics": ["catalog", "seo", "speed", "conversion"] }

// 2. 商品SEO优化
{ "action": "optimize", "products": [{ "title": "Cool Widget", "description": "A cool widget", "tags": "" }] }

// 3. 弃购挽回策略
{ "action": "recovery", "abandonedRate": 78, "avgOrderValue": 55 }

// 4. 多市场扩张
{ "action": "expand", "targetMarkets": ["UK", "DE", "CA"], "currentCurrency": "USD" }
```
