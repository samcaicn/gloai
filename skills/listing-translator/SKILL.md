---
id: "com.tupautochrome.skills.listing-translator"
name: "Listing多语言翻译"
name_en: "Listing Multilingual Translator"
version: "1.0.0"
author: "tupAI"
license: "MIT"
icon: ""

category: "data"
software_names: ["亚马逊", "Amazon", "eBay", "Shopify"]
software_names_en: ["Amazon", "eBay", "Shopify"]
tags: ["translation", "localization", "listing", "multilingual", "seo", "cross-border", "i18n"]
keywords: ["翻译", "多语言", "本地化", "Listing", "SEO", "关键词本地化", "多站点"]

capabilities:
  - id: "translate_listing"
    name: "Listing翻译"
    description: "将商品Listing翻译为多语言版本，含SEO关键词本地化"
    inputs:
      - { name: "listing", type: "object", required: true, description: "{ title, bullets[], description, keywords[] }" }
      - { name: "targetLanguages", type: "array", items: "string", required: true, description: "de/fr/it/es/ja/ar/..." }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "translations", type: "array" }

  - id: "seo_keywords"
    name: "SEO关键词本地化"
    description: "为目标市场生成本地化关键词"
    inputs:
      - { name: "keywords", type: "array", items: "string", required: true }
      - { name: "targetMarket", type: "string", required: true }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "localized", type: "object" }

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

# Listing多语言翻译 v1.0

> 跨境电商 Listing 多语言翻译和 SEO 本地化工具。支持德语/法语/意大利语/西班牙语/日语/阿拉伯语等主流市场，保留关键词密度和 SEO 结构。

## 核心能力

- **标题翻译**: 保留核心关键词和卖点结构的多语言标题
- **五点描述**: 地道本地化翻译，符合当地电商用语习惯
- **A+内容**: 品牌故事和产品描述的深度本地化
- **SEO关键词**: 生成当地消费者真实使用的搜索词
- **文化适配**: 自动识别和调整文化敏感词和视觉偏好

## 使用示例

```json
// 1. Listing翻译
{ "action": "translate", "listing": { "title": "Premium Yoga Mat - Non-Slip Eco-Friendly Exercise Mat for Home Gym", "bullets": ["Non-slip TPE material for safe practice", "Eco-friendly and free from harmful chemicals", "Includes carry strap for easy transport"], "description": "Experience the perfect yoga session with our premium mat...", "keywords": ["yoga mat", "exercise mat", "fitness mat"] }, "targetLanguages": ["de", "fr", "ja"] }

// 2. SEO关键词本地化
{ "action": "keywords", "keywords": ["yoga mat", "exercise mat"], "targetMarket": "DE" }
```
