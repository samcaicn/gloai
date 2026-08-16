---
id: "com.tupautochrome.skills.tiktok-trend-tracker"
name: "TikTok热品追踪"
name_en: "TikTok Trend Tracker"
version: "1.0.0"
author: "AIMarketing"
license: "MIT"
icon: ""

category: "data"
software_names: ["TikTok", "抖音"]
software_names_en: ["TikTok", "Douyin"]
tags: ["tiktok", "trending", "cross-border", "ecommerce", "tiktok-shop", "viral", "product-research"]
keywords: ["TikTok", "热品", "趋势", "带货", "爆款", "tiktok shop", "trending", "viral"]

capabilities:
  - id: "trending_search"
    name: "TikTok热品搜索"
    description: "搜索TikTok Shop热销商品，获取销量、GMV、带货视频数据"
    inputs:
      - { name: "keywords", type: "array", items: "string", required: true }
      - { name: "marketplace", type: "string", default: "US", description: "US/UK/ID/TH/VN"}
      - { name: "maxResults", type: "number", default: 30 }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "products", type: "array" }

  - id: "video_analytics"
    name: "带货视频分析"
    description: "分析商品关联带货视频的播放量、互动数据"
    inputs:
      - { name: "productId", type: "string", required: true }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "videos", type: "array" }

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

# TikTok热品追踪 v1.0

> 实时追踪TikTok Shop热销商品和带货趋势，支持多站点（US/UK/ID/TH/VN），提供销量数据、GMV估算、带货视频分析和爆款预测。

## 核心能力

- **热品搜索**: 按关键词搜索TikTok Shop商品，获取价格、销量、GMV数据
- **带货视频**: 查询商品关联的带货视频播放量、点赞、评论、分享数据
- **达人分析**: 分析带货达人的粉丝画像、带货效率
- **趋势监测**: 实时追踪TikTok热销榜单变化，提前发现爆款信号
- **竞品监控**: 监控特定品类或店铺的商品表现变化

## 使用示例

```json
// 1. 搜索TikTok热品
{ "action": "search", "keywords": ["skincare", "beauty"], "marketplace": "US", "maxResults": 20 }

// 2. 查看商品带货视频
{ "action": "videos", "productId": "123456789", "marketplace": "US" }

// 3. 趋势分析报告
{ "action": "trending", "category": "beauty", "marketplace": "US" }

// 4. 达人分析
{ "action": "creator", "creatorId": "tiktok_creator_123" }
```
