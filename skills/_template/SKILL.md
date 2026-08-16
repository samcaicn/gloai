---
id: "com.tupautochrome.skills.template"   # 反域名，全局唯一
name: "技能模板"
name_en: "Skill Template"
version: "1.0.0"                           # SemVer
author: "your-org"
license: "MIT"
homepage: "https://github.com/your-org/your-skill"
icon: "assets/icon.png"

# 分类与搜索
category: "web"                            # web | desktop | mobile | data | misc
software_names: ["软件中文名"]              # 该技能支持的目标软件
software_names_en: ["Software English Name"]
tags: ["example", "template"]
keywords: ["关键词1", "keyword1"]

# 能力声明
capabilities:
  - id: "main_action"
    name: "主动作"
    description: "技能的主入口"
    inputs:
      - { name: "goal", type: "string", required: true, description: "任务目标" }
      - { name: "recognition", type: "array", items: "string", default: ["cdp","uia","ocr","vlm"] }
      - { name: "maxRounds", type: "number", default: 50 }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "trace", type: "array" }

# 依赖
runtime:
  engine: "js"
  engineVersion: ">=1.0.0 <2.0.0"
  caps:
    - "cap.cdp@^1.0.0"
    - "cap.recognize@^1.0.0"
    - "cap.control@^1.0.0"
    - "cap.flowchart@^1.0.0"
  permissions:
    - "http:fetch:*"
    - "storage:readwrite"

# CLI 工具依赖（技能执行前自动检测）
cli_deps:
  - name: "git"
    min_version: "2.0"
    install_hint: "winget install Git.Git"

# 升级策略
distribution:
  channel: "stable"                        # stable | beta | nightly
  minAppVersion: "0.5.0"
  maxAppVersion: "2.0.0"
  rollout:
    percentage: 100
    targetUsers: []

# 签名
signing:
  algorithm: "ed25519"
  publicKey: ""
---

# 技能名称

> 本文件是 `skills/_template/` 标准模板的一部分。拷贝此目录后，把上面的 frontmatter 字段（尤其是 `id` / `name` / `software_names` / `runtime.caps`）替换成你自己的内容。

## 简介

3-5 行人读说明：说明这个技能做什么、面向哪个软件、识别链走哪几层、控制流提供哪些按钮。读者读完应能判断是否需要安装此技能。

## 使用示例

```json
// 1. 按软件名搜索（前端 AutomationPage 入口）
{ "action": "search_software", "softwareName": "软件中文名", "softwareNameEn": "Software English Name" }

// 2. 启动执行
{ "action": "execute", "goal": "任务目标", "recognition": ["cdp","uia","ocr","vlm"], "maxRounds": 50 }

// 3. 迷你悬浮窗控制
{ "action": "step_once" }
{ "action": "pause" }
{ "action": "resume" }
{ "action": "stop" }

// 4. 停止后回看
{ "action": "get_flowchart" }
{ "action": "get_judgments" }
{ "action": "get_trace" }
```

## 节点说明

下表对应 `flowchart.json` 中每个节点的语义。拷贝模板后请按你的实际节点重写。

| 节点 id | 类型 | 标签 | 识别链 | 说明 |
|---------|------|------|--------|------|
| `start` | start | 开始 | — | 流程入口 |
| `act`   | process | 执行动作 | `["cdp"]` | 主动作节点，演示用 |
| `end`   | end | 结束 | — | 流程出口 |

`connections` 描述节点间的跳转，`judgments` 描述 decision 节点的判断规则与命中后跳转目标，详见 `flowchart.json`。

## Changelog

- 1.0.0: 初始版本
