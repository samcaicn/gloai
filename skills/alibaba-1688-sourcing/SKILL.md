---
id: "com.tupautochrome.skills.alibaba-1688-sourcing"
name: "1688货源搜索"
name_en: "1688 Sourcing Search"
version: "1.0.0"
author: "tupAI"
license: "MIT"
icon: ""

category: "data"
software_names: ["1688", "Alibaba"]
software_names_en: ["1688", "Alibaba"]
tags: ["1688", "sourcing", "wholesale", "cross-border", "ecommerce", "supplier", "product-research"]
keywords: ["1688", "货源", "批发", "供应商", "选品", "采购", "sourcing", "wholesale", "supplier"]

capabilities:
  - id: "product_search"
    name: "1688商品搜索"
    description: "在1688批发平台搜索商品，获取价格、销量、供应商信息"
    inputs:
      - { name: "keywords", type: "array", items: "string", required: true, description: "搜索关键词" }
      - { name: "maxResults", type: "number", default: 30 }
      - { name: "filters", type: "object", description: "筛选条件 { minPrice, maxPrice, minSales, sortBy }"}
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "products", type: "array" }

  - id: "supplier_analysis"
    name: "供应商分析"
    description: "分析供应商资质、诚信档案、生产能力"
    inputs:
      - { name: "supplierId", type: "string", required: true }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "supplier", type: "object" }

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

# 1688货源搜索 v1.0

> 专为跨境电商卖家打造的1688批发平台货源搜索工具，支持关键词搜索、价格筛选、销量排行、供应商信用分析、跨平台比价（1688→Amazon）。

## 核心能力

- **商品搜索**: 按关键词搜索1688商品，获取价格、起批量、累计销量、供应商
- **供应商分析**: 查询供应商资质、诚信通年限、响应速度、生产能力
- **跨平台比价**: 将1688价格换算为Amazon/eBay等平台参考售价和利润
- **热销榜单**: 获取1688行业热销/趋势商品榜单
- **以图搜图**: 通过商品图片搜索1688相似货源

## 使用示例

```json
// 1. 搜索货源
{ "action": "search", "keywords": ["瑜伽垫", "运动地垫"], "maxResults": 30 }

// 2. 筛选搜索
{ "action": "search", "keywords": ["蓝牙耳机"], "filters": { "minPrice": 10, "maxPrice": 50, "minSales": 1000 } }

// 3. 供应商分析
{ "action": "supplier", "supplierId": "cn123456" }

// 4. 跨平台比价（估算Amazon售价和利润）
{ "action": "compare", "productId": "123456", "targetMarket": "US" }

// 5. 热销榜单
{ "action": "trending", "category": "家居日用" }
```
