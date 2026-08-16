---
id: "kuaiju-viewer"
name: kuaiju-viewer
description: 快剧快捷入口 — 一键打开快剧（快捷键视频）网页 https://kuaiju2c.tuptup.top
version: 1.0.0
author: AIMarketing
tags: [kuaiju, video, short-video, viewer, iframe, shortcut]
entrypoints: [main]
inputs:
  type: object
  properties:
    action:
      type: string
      enum: [open]
      description: 技能动作（仅支持 open）
outputs:
  type: object
dependencies: []
---

# 快剧 (Kuaiju Viewer v1)

一键打开快剧（快捷键视频）网页的轻量技能。

## 功能

| 动作 | 说明 |
|------|------|
| **open** | 返回快剧网页 URL，由宿主在 iframe 中打开 |

## 使用示例

```json
{ "action": "open" }
```

返回示例：

```json
{ "_kuaiju": true, "url": "https://kuaiju2c.tuptup.top" }
```

## 集成方式

宿主根据 `_kuaiju: true` 标记在主界面 iframe 中打开 `url`。
