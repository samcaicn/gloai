---
id: "wechat-publisher"
name: wechat-publisher
description: 公众号文章撰写与发布 — 交互式配置、热点监测、7种写作框架、风格学习、质量检查、去AI化、一键发布到微信公众号草稿箱
version: 3.0.0
author: tupAI
tags: [wechat, writing, publishing, content-creation, weixin, social-media, automation, copywriting, marketing]
entrypoints: [main]
inputs:
  type: object
  properties:
    action:
      type: string
      enum: [setup, profile, write, monitor, publish, auto, status, learn, check, deai, upload]
      description: 技能动作
    topic:
      type: string
      description: 文章话题（write 动作）
    content:
      type: string
      description: 文章内容（check / deai 动作）
    skipConfirm:
      type: boolean
      description: 跳过确认步骤
outputs:
  type: object
dependencies: []
---

# 公众号文章技能 (WeChat Publisher v3)

交互式公众号文章撰写与发布技能。通过 LLM 对话了解用户偏好，自动监测全网热点，支持 7 种写作框架，可学习用户历史文章风格，具备质量检查和去 AI 化处理能力，一键发布到微信公众号草稿箱。

## 功能

| 动作 | 说明 |
|------|------|
| **setup** | LLM 多轮对话收集公众号信息（品牌名、领域、风格、读者画像、关键词） |
| **profile** | 查看/修改当前公众号配置 |
| **write** | 输入话题 → 生成大纲 → 选择写作框架 → 生成文章 → 修改/发布 |
| **monitor** | 全网搜索热点 → LLM 智能选题 → 写作 → 发布 |
| **publish** | 将草稿发布到微信公众号草稿箱（CDP 自动操作） |
| **auto** | 全自动闭环：监测 → 选题 → 写作 → 发布 |
| **status** | 查看当前配置和草稿状态 |
| **learn** | 上传历史文章 → 提取风格指纹 → 后续写作自动匹配风格 |
| **check** | 对文章进行 6 维质量评分 + 改进建议 |
| **deai** | 将 AI 生成文章处理得更自然、更像真人写作 |
| **upload** | 将本技能发布到 MCP 技能市场 |

## 7 种写作框架

1. **痛点共鸣** — 抛出读者痛点引发共鸣，自然过渡到解决方案
2. **故事叙述** — 用一个好故事贯穿全文，带入感强
3. **清单列表** — "N 个方法/技巧/趋势"，条理清晰，易转发收藏
4. **对比分析** — A vs B 优劣对比，帮读者做决策
5. **热点解读** — 借热点事件快速切入，深度分析
6. **观点输出** — 独特观点 + 论证，引发讨论和转发
7. **复盘总结** — 项目/事件复盘，输出可复用的经验方法

## 使用示例

```json
{ "action": "setup" }
{ "action": "write", "topic": "2026年AI趋势分析" }
{ "action": "monitor" }
{ "action": "learn" }
{ "action": "check", "content": "文章内容..." }
{ "action": "deai", "content": "AI生成的文章..." }
```

## 架构

```
用户交互层: cap.ui.prompt (弹窗) + LLM 对话
持久化层:   cap.storage (localStorage) — wechat_profile / wechat_draft / wechat_style / wechat_conversation
热点监测:   cap.cdp (Chrome CDP) — 百度/Bing 搜索
文章发布:   cap.cdp — 操作 mp.weixin.qq.com 编辑器
写作引擎:   cap.llm.complete — 多框架模板 + 风格指纹注入
```
