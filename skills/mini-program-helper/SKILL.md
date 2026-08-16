---
id: "com.tupautochrome.skills.mini-program-helper"
name: "微信小程序开发助手"
name_en: "WeChat Mini Program Helper"
version: "1.0.0"
author: "AIMarketing"
license: "MIT"
icon: ""

# 分类与搜索
category: "mobile"
software_names: ["微信", "微信小程序", "微信开发者工具"]
software_names_en: ["WeChat", "Weixin", "WeChat DevTools"]
tags: ["mini-program", "wechat", "weixin", "小程序", "development", "guidance", "template", "publish", "mobile", "cross-platform"]
keywords: ["小程序", "微信小程序", "微信开发", "小程序开发", "WXML", "WXSS", "小程序模板", "小程序发布", "小程序审核", "小程序备案", "云开发", "微信支付", "小程序设计", "小程序优化", "mini-program"]

# 能力声明
capabilities:
  - id: "create"
    name: "项目搭建"
    description: "从零搭建小程序项目，生成 app.json 配置和首屏代码"
    inputs:
      - { name: "projectName", type: "string", required: true, description: "项目名称" }
      - { name: "appId", type: "string", description: "小程序 AppID（默认测试号）" }
      - { name: "description", type: "string", description: "项目描述" }
      - { name: "template", type: "string", description: "框架偏好: 原生/uniapp/taro" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "result", type: "object", description: "{ setupSteps, appJson, firstPage }" }

  - id: "guidance"
    name: "开发指导"
    description: "LLM 驱动的交互式开发指导，覆盖组件/API/生命周期/登录/支付/云开发/设计规范等"
    inputs:
      - { name: "topic", type: "string", description: "咨询主题" }
      - { name: "question", type: "string", description: "具体问题" }
      - { name: "experience", type: "string", default: "新手", enum: ["新手", "进阶", "专家"] }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "result", type: "object", description: "结构化指导内容" }

  - id: "template"
    name: "代码模板"
    description: "生成常用页面代码模板（列表/表单/详情/Tab/登录），或 LLM 按功能描述生成"
    inputs:
      - { name: "pageType", type: "string", enum: ["list", "form", "detail", "tabs", "login"], description: "内置模板类型" }
      - { name: "feature", type: "string", description: "自定义功能描述（LLM 生成）" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "template", type: "object", description: "{ wxml, wxss, js, json }" }

  - id: "publish"
    name: "发布审核"
    description: "发布流程指导，按阶段提供备案/提审/驳回处理/运营建议"
    inputs:
      - { name: "stage", type: "string", enum: ["准备", "待审核", "被驳回", "已发布"], description: "当前阶段" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "result", type: "object", description: "阶段针对性指导" }

  - id: "optimize"
    name: "性能优化"
    description: "针对首屏/分包/渲染/启动等维度提供优化方案"
    inputs:
      - { name: "focus", type: "string", enum: ["首屏", "分包", "渲染", "启动", "综合"], description: "优化重点" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "result", type: "object", description: "优化方案" }

  - id: "troubleshoot"
    name: "问题排查"
    description: "根据问题描述或错误码诊断原因并给出修复方案"
    inputs:
      - { name: "issue", type: "string", description: "问题现象描述" }
      - { name: "errorCode", type: "string", description: "错误码" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "result", type: "object", description: "{ rootCause, steps, solution }" }

# 依赖
runtime:
  engine: "js"
  engineVersion: ">=1.0.0 <2.0.0"
  caps:
    - "cap.llm@^1.0.0"
    - "cap.flowchart@^1.0.0"
    - "cap.runtime@^1.0.0"
  permissions:
    - "http:fetch:*"

# 升级策略
distribution:
  channel: "stable"
  minAppVersion: "0.5.0"
  rollout:
    percentage: 100

# 签名
signing:
  algorithm: "ed25519"
  publicKey: ""
---

# 微信小程序开发助手

> LLM 驱动的全流程微信小程序开发指导技能。覆盖从项目搭建、代码编写、发布审核到性能优化的完整开发周期。

## 简介

微信小程序开发助手是一个基于 LLM 的交互式指导技能，帮助开发者解决小程序开发中的各种问题。它内置了 15 个知识领域（组件/API/生命周期/WXML/WXSS/云开发/登录/支付/设计规范/发布审核/性能优化/推广营销/常见坑/Skyline 引擎），支持 7 种操作模式，可生成完整页面代码模板。

适合人群：零基础新手到进阶开发者。

## 使用示例

```json
// 1. 项目搭建
{ "action": "create", "projectName": "我的商城", "template": "原生" }

// 2. 开发指导
{ "action": "guidance", "topic": "云开发", "experience": "新手" }

// 3. 生成代码模板
{ "action": "template", "pageType": "list" }
{ "action": "template", "feature": "商品评价页面带星级评分" }

// 4. 发布流程
{ "action": "publish", "stage": "准备" }

// 5. 性能优化
{ "action": "optimize", "focus": "首屏" }

// 6. 问题排查
{ "action": "troubleshoot", "issue": "页面白屏", "errorCode": "86369" }

// 7. 知识查询
{ "action": "query", "topic": "payment" }
```

## 节点说明

| 节点 id | 类型 | 标签 | 说明 |
|---------|------|------|------|
| `start` | start | 开始 | 流程入口 |
| `choose` | decision | 选择服务 | 根据 action 分支到对应节点 |
| `project_setup` | process | 项目搭建 | 生成项目结构和首屏代码 |
| `dev_guidance` | process | 开发指导 | LLM 驱动开发问答 |
| `code_template` | process | 代码模板 | 内置模板/LLM 生成 |
| `publish_guide` | process | 发布流程 | 备案/提审/驳回处理 |
| `optimize_guide` | process | 性能优化 | 针对性优化方案 |
| `troubleshoot` | process | 问题排查 | 诊断并修复 |
| `report` | process | 报告 | 汇总结果 |
| `end` | end | 结束 | 流程出口 |

## Changelog

- 1.0.0: 初始版本 — 7 种操作模式 + 15 知识领域 + 5 内置代码模板 + LLM 自定义模板生成
