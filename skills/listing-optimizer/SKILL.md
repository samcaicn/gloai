---
id: "com.tupautochrome.skills.listing-optimizer"
name: "Listing优化器"
name_en: "Listing Optimizer"
version: "1.0.0"
author: "AIMarketing"
license: "MIT"
icon: ""

category: "data"
software_names: ["亚马逊", "Amazon", "eBay"]
software_names_en: ["Amazon", "eBay"]
tags: ["listing", "optimization", "seo", "keywords", "cross-border", "ecommerce", "copywriting", "conversion"]
keywords: ["listing优化", "SEO", "关键词", "标题优化", "描述优化", "A+内容", "转化率", "Amazon listing"]

capabilities:
  - id: "title_optimize"
    name: "标题优化"
    description: "AI优化Listing标题，提升搜索排名和点击率"
    inputs:
      - { name: "productInfo", type: "object", required: true, description: "产品信息 { title, features, keywords, category }" }
      - { name: "marketplace", type: "string", default: "US" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "titles", type: "array" }

  - id: "bullet_optimize"
    name: "五点描述优化"
    description: "优化五点描述，突出卖点和转化要素"
    inputs:
      - { name: "currentBullets", type: "array", items: "string" }
      - { name: "productInfo", type: "object", required: true }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "bullets", type: "array" }

  - id: "description_generate"
    name: "产品描述生成"
    description: "AI生成产品描述和A+内容"
    inputs:
      - { name: "productInfo", type: "object", required: true }
      - { name: "style", type: "string", default: "professional", description: "professional/emotional/technical" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "description", type: "string" }

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

# Listing优化器 v1.0

> AI驱动的跨境电商Listing全链路优化工具，覆盖标题、五点描述、产品描述、A+内容、关键词布局、搜索词优化。

## 核心能力

- **标题优化**: AI生成高点击率标题，融入核心关键词和卖点
- **五点描述**: 优化Bullet Points，突出差异化价值和转化要素
- **描述生成**: 生成产品长描述和A+模块内容
- **关键词研究**: 提取高价值搜索词，优化后台Search Terms
- **竞品对标**: 分析Top竞品Listing结构，生成优化建议
- **多语言适配**: 支持US/UK/DE/FR/IT/ES/JP等站点

## 使用示例

```json
// 1. 标题优化
{ "action": "title", "productInfo": { "title": "Yoga Mat", "features": ["non-slip", "eco-friendly", "6mm thickness", "carry strap"], "keywords": ["yoga mat", "exercise mat", "fitness mat"], "category": "Sports" }, "marketplace": "US" }

// 2. 五点描述优化
{ "action": "bullets", "currentBullets": ["Non-slip surface", "Eco-friendly material"], "productInfo": { "features": ["non-slip", "eco-friendly TPE", "6mm thick", "carry strap included", "72x24 inches", "easy clean"], "keywords": ["yoga mat", "exercise mat"] } }

// 3. 产品描述生成
{ "action": "description", "productInfo": { "features": ["..."], "keywords": ["..."], "targetAudience": "yoga beginners" }, "style": "professional" }

// 4. 搜索词优化
{ "action": "searchTerms", "keywords": ["yoga mat", "exercise mat", "pilates mat"], "marketplace": "US" }

// 5. 完整Listing分析+优化
{ "action": "fullOptimize", "listing": { "title": "...", "bullets": [...], "description": "..." }, "marketplace": "US" }
```
