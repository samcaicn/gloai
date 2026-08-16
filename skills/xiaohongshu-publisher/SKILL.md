---
id: "xiaohongshu-publisher"
name: xiaohongshu-publisher
description: 小红书文案技能 — 全网热点监测 → 自动撰写 → 配图 → 发布到微信公众号草稿箱备份。输入品牌词+目标关键词+监测间隔+输出间隔即可全自动运行
version: 1.0.0
author: tupAI
tags: [xiaohongshu, redbook, content-creation, social-media, monitor, auto-writing, marketing]
entrypoints: [main]
inputs:
  type: object
  properties:
    action:
      type: string
      enum: [monitor, status, stop]
      description: 技能动作
    brandKeywords:
      type: string
      description: 自有业务品牌词，多个用逗号分隔
    targetKeywords:
      type: string
      description: 监测目标关键词，多个用逗号分隔
    monitorInterval:
      type: number
      description: 监测间隔（分钟，默认120）
    outputInterval:
      type: number
      description: 输出间隔（分钟，默认1440）
outputs:
  type: object
dependencies: []
---

# 小红书文案技能 (Xiaohongshu Publisher v1)

全网热点监测 → 自动撰写小红书文案 → 自动配图 → 发布到微信公众号草稿箱备份。

## 功能

| 动作 | 说明 |
|------|------|
| **monitor** | 启动定时监测与自动输出循环 |
| **status** | 查看当前运行状态、最近监测/输出时间、累计篇数 |
| **stop** | 停止监测循环 |

## 工作流

```
定时监测 (monitorInterval)
  └→ 抓取热点: 小红书 + 百度
      └→ LLM 评估相关性
          └→ 命中自有品牌 → 跳过
              └→ 命中目标关键词 → 加入候选
                  └→ 定时输出 (outputInterval)
                      └→ LLM 撰写小红书文案
                          └→ 配图 (默认占位)
                              └→ 发布到微信公众号草稿箱
```

## 使用示例

```json
{ "action": "monitor", "brandKeywords": "tupAI,TraceAuto", "targetKeywords": "AI 自动化,RPA,智能体", "monitorInterval": 120, "outputInterval": 1440 }
{ "action": "status" }
{ "action": "stop" }
```

## 状态字段

| 字段 | 说明 |
|------|------|
| `running` | 是否在运行 |
| `lastMonitor` | 上次监测时间戳 |
| `lastOutput` | 上次输出时间戳 |
| `topics` | 已抓取的话题 |
| `posts` | 已发布的文章 |
| `round` | 当前轮次 |

## 架构

```
cap.cdp       — 小红书/百度搜索抓取
cap.llm       — 话题相关性评估、文案撰写
cap.storage   — 状态持久化（trace_xiaohongshu_publisher_state）
cap.runtime   — 定时器调度
```
