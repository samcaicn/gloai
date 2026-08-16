---
id: "com.tupautochrome.skills.trace-auto"   # 反域名，全局唯一
name: "Trace Auto 自动化"
name_en: "Trace Auto"
version: "6.0.0"                              # SemVer
author: "tupAI"
license: "Apache-2.0"
homepage: "https://github.com/tupAI/tupautochrome"
icon: "assets/icon.png"

# 分类与搜索
category: "desktop"                           # web | desktop | mobile | data | misc
software_names: ["Trae", "Trae IDE"]          # 该技能支持的目标软件
software_names_en: ["Trae", "Trae IDE"]
tags: ["trae", "automation", "cdp", "uia", "ocr", "vlm", "flowchart", "rpa", "browser", "driving", "loop", "ide"]
keywords: ["自动化", "trae", "流程图", "识别降级", "迷你悬浮窗", "automation", "flowchart"]

# 能力声明
capabilities:
  - id: "main_action"
    name: "主动作"
    description: "Trae IDE 自动化的主入口 — 含流程图查看 / 执行 / 录制 / 控制流 / CDP 检测 / 页面读取 / 页面操作 / 条件回复"
    inputs:
      - { name: "action", type: "string", required: true, description: "技能动作（见下方动作分组）" }
      - { name: "softwareName", type: "string", description: "[search_software] 软件中文名" }
      - { name: "softwareNameEn", type: "string", description: "[search_software] 软件英文名(可选)" }
      - { name: "goal", type: "string", description: "[execute/start/generate_followup] 任务目标" }
      - { name: "maxRounds", type: "number", default: 50, description: "[execute/start] 最大轮次" }
      - { name: "idleTimeoutSec", type: "number", default: 60, description: "[execute/start] 等待超时秒" }
      - { name: "recognition", type: "array", items: "string", default: ["cdp","uia","ocr","vlm"], description: "[execute] 识别能力链顺序" }
      - { name: "conditions", type: "array", description: "[set_conditions/summarize_conditions] 条件列表" }
      - { name: "buttonText", type: "string", description: "[click_button] 按钮文本" }
      - { name: "text", type: "string", description: "[type_input/type_and_send] 输入文本" }
      - { name: "aiText", type: "string", description: "[check_and_reply/check_only/generate_followup] AI 回复文本(不传则自动读取)" }
      - { name: "keyword", type: "string", default: "trae", description: "[check_page] 页面关键词" }
      - { name: "timeoutSec", type: "number", default: 60, description: "[wait_idle] 超时秒数" }
      - { name: "threshold", type: "number", default: 3, description: "[detect_stuck] 卡住阈值" }
      - { name: "waitAfterMs", type: "number", default: 8000, description: "[type_and_send/check_and_reply] 发送后等待毫秒" }
      - { name: "skipSummarize", type: "boolean", description: "[set_conditions] 跳过 LLM 精炼" }
      - { name: "selectors", type: "object", description: "自定义 CSS 选择器(可选)" }
      - { name: "steps", type: "array", description: "[run_steps] 步骤列表" }
      - { name: "nodeId", type: "string", description: "[add_breakpoint/remove_breakpoint] 节点 id" }
      - { name: "skillId", type: "string", description: "[check_upgrade/upgrade/rollback] 技能 id" }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "status", type: "string" }
      - { name: "rounds", type: "number" }
      - { name: "flowchart", type: "object" }
      - { name: "trace", type: "array" }

# 依赖
runtime:
  engine: "js"
  engineVersion: ">=1.0.0 <2.0.0"
  caps:
    - "cap.cdp@^1.0.0"
    - "cap.uia@^1.0.0"
    - "cap.ocr@^1.0.0"
    - "cap.vlm@^1.0.0"
    - "cap.llm@^1.0.0"
    - "cap.storage@^1.0.0"
    - "cap.ui@^1.0.0"
    - "cap.runtime@^1.0.0"
    - "cap.recognize@^1.0.0"
    - "cap.control@^1.0.0"
    - "cap.flowchart@^1.0.0"
    - "cap.skillMarket@^1.0.0"
    - "cap.server@^1.0.0"
  permissions:
    - "http:fetch:*"
    - "storage:readwrite"

# 升级策略
distribution:
  channel: "stable"                           # stable | beta | nightly
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

# Trace Auto (v6)

Trae IDE 自动化技能。把「软件搜索 → 执行 → 迷你悬浮窗 → 单步/暂停/停止 → 回看流程图」整条线封装为一份标准技能文件。识别能力按 `CDP > UIA > OCR > VLM` 链式降级，并对外暴露流程图与判断节点，方便 UI 端在停止后完整重放。

> 本技能是 `skills/_template/` 标准模板的**参考实例**，目录结构、frontmatter、handler 三段式导出、流程图 schema 都与模板对齐。

## 简介

本技能驱动 Trae IDE 完成「对话式代码任务」的自动推进：

- **识别层（See）**：`cap.recognize.chain` 按 CDP > UIA > OCR > VLM 链式降级，DOM 直读准确率 100%，VLM 兜底处理复杂图像
- **控制层（Think/Act）**：流程图驱动循环，每节点先 `cap.control.check` 再执行；迷你悬浮窗提供单步/暂停/继续/停止
- **回放层**：`cap.flowchart` 记录每个节点的命中/失败/耗时，停止后前端用 `traceMap` 高亮完整轨迹

## 标准技能文件格式（v6）

```
skills/trace-auto/
├── SKILL.md          # 本文件 — frontmatter 元数据 + 人读说明
├── index.js          # 运行时 handler（handler + lifecycle + debug 三段式导出）
├── flowchart.json    # 标准流程图配置（含 $schema/skillId/entry/metadata）
├── USAGE.md          # 使用流程：搜索 → 加载 → 执行 → 停止 → 回放
├── DEBUG.md          # 调试流程：断点 / 单步 / 变量监视 / trace
└── UPGRADE.md        # 升级流程：SemVer / 灰度 / 回滚 / 市场元数据
```

`flowchart.json` 遵循标准 schema（参考 UiPath project.json + Robocorp robot.yaml + Robot Framework *** Settings ***），字段规范见 `_template/SKILL.md`。

## 识别能力链（See 机制）

参考 UiPath See/Think 二段架构，本技能的 See 层按下表优先级降级：

| 层级 | 能力 | 速度 | 适用 | 备注 |
|------|------|------|------|------|
| L0 | **CDP** | <100ms | Electron/Web 应用 | DOM 直读，准确率 100% |
| L1 | **UIA** | 100-500ms | Windows 原生控件 | Trae 等 Electron 渲染层不可见，自动跳过 |
| L2 | **OCR** | 500ms-2s | 任意文字识别 | tesseract.js 局部截图优化 |
| L3 | **VLM** | 2-5s | 复杂图像理解 | 多模态模型兜底 |

调用方传 `recognition: ["cdp","uia","ocr","vlm"]` 自定义顺序，默认即此顺序。识别由 `cap.recognize.chain(task, tiers)` 统一调度（见 `src-tauri/src/skills/capabilities.js`）。

## 节点说明

下表对应 `flowchart.json` 中每个节点的语义：

| 节点 id | 类型 | 标签 | 识别链 | 说明 |
|---------|------|------|--------|------|
| `start`    | start    | 开始 | — | 流程入口 |
| `ensure`   | process  | 确保软件支持连接 | `["cdp","uia"]` | 验证 IDE 已连接 |
| `read`     | process  | 读取页面状态 | `["cdp","ocr","vlm"]` | 读取对话轮次 / 运行态 / 错误 / 按钮 |
| `running?` | decision | AI 在运行? | — | J1：stop 按钮 enabled → running=true → wait |
| `wait`     | process  | 等待 AI 空闲 | — | 轮询直到 AI 空闲 |
| `act`      | process  | 执行下一步 | — | 点击确认/运行按钮 或 发送输入 或 条件回复 |
| `errors?`  | decision | 检测到错误? | — | J2：DOM 含 error/warning/danger → prompt |
| `stuck?`   | decision | 卡住? | — | J3：3 轮无变化 → prompt |
| `prompt`   | io       | 向用户提问/发送指令 | — | 弹窗询问用户如何处理 |
| `loop`     | process  | 回到读取页面 | — | LLM 生成跟进指令后回到 read |
| `end`      | end      | 结束 | — | 流程出口 |

## 操作流程（UI 端）

1. **进入「自动化工坊」** → 输入软件中/英文名 → 调 `search_software` 检索服务器技能（走 `cap.skillMarket.searchBySoftware`）
2. **检索命中** → 「执行」按钮可点；未命中 → 「执行」置灰。「录制」按钮永远可点
3. **点「执行」** → 主窗口隐藏 → 弹出迷你悬浮窗（fw_open） → 同时按识别链启动 CDP/UIA/OCR/VLM
4. **迷你悬浮窗支持**：单步(`step_once`) / 暂停(`pause`) / 继续(`resume`) / 停止(`stop`) — 全部走 `cap.control`
5. **点「停止」** → 主窗口恢复 → 调 `get_flowchart` 完整渲染流程图与判断节点 + `get_trace` 高亮轨迹

## 动作分组

| 分组 | 动作 | 说明 |
|------|------|------|
| **流程图** | `get_flowchart` / `get_judgments` / `get_trace` | 取流程图节点+边、取判断规则、取执行轨迹 |
| **执行入口** | `search_software` / `execute` / `record` | 按软件名搜服务器技能 / 启动执行 / 启动录制 |
| **控制流** | `step_once` / `pause` / `resume` / `stop` | 迷你悬浮窗控制按钮（调 cap.control） |
| **断点** | `add_breakpoint` / `remove_breakpoint` / `clear_breakpoints` | 断点管理（调 cap.control） |
| **升级** | `check_upgrade` / `upgrade` / `rollback` | 升级管理（调 cap.skillMarket） |
| **驱动循环** | `start` / `status` / `run_steps` | 旧版兼容 |
| **CDP 检测** | `ensure_cdp` / `find_exe` / `scan_ports` / `targets` / `check_page` | 同 v5 |
| **页面读取** | `read_state` / `wait_idle` / `detect_stuck` / `reset_stuck` / `read_input` / `count_turns` / `check_running` | 同 v5 |
| **页面操作** | `click_button` / `click_action_buttons` / `click_send` / `click_stop` / `type_input` / `type_and_send` / `verify_input` / `clear_input` / `send_input` | 同 v5 |
| **条件回复** | `set_conditions` / `get_conditions` / `check_and_reply` / `generate_followup` / `summarize_conditions` / `clear_conditions` / `check_only` | 同 v5 |

## 使用示例

```json
{ "action": "search_software", "softwareName": "Trae", "softwareNameEn": "Trae IDE" }
{ "action": "execute", "goal": "实现登录功能", "recognition": ["cdp","uia","ocr","vlm"] }
{ "action": "step_once" }
{ "action": "pause" }
{ "action": "resume" }
{ "action": "stop" }
{ "action": "get_flowchart" }
{ "action": "get_judgments" }
{ "action": "get_trace" }
{ "action": "add_breakpoint", "nodeId": "act" }
{ "action": "check_upgrade" }
{ "action": "type_and_send", "text": "请帮我添加单元测试" }
{ "action": "set_conditions", "conditions": ["AI 询问需求", "AI 输出代码", "AI 报错"] }
{ "action": "check_and_reply" }
```

## 架构

```
cap.cdp        — Chrome DevTools Protocol 控制 Trae（L0）
cap.uia        — Windows UI Automation 适配器（L1, 注入点）
cap.ocr        — OCR 文字识别（L2, 注入点）
cap.vlm        — VLM 视觉理解（L3, 注入点）
cap.recognize  — 多层识别降级链（CDP>UIA>OCR>VLM 统一调度）
cap.runtime    — 等待、日志、uuid
cap.llm        — 追问生成、条件精炼
cap.storage    — 条件持久化(trace_auto_conditions) + 执行轨迹
cap.ui         — 用户交互弹窗
cap.control    — 执行控制信号（暂停/单步/停止/断点）
cap.flowchart  — 流程图访问层 + 执行 trace 记录/序列化/导出
cap.server     — 服务器侧技能市场 API（搜索/详情/流程图/下载/上报）
cap.skillMarket — 技能市场客户端（加载/列表/升级/回滚）
```

## Changelog

- 6.0.0: 标准化为模板参考实例 — frontmatter 升级为标准格式（id 反域名 + capabilities + runtime.caps + distribution + signing）；index.js 移除临时桩改用 cap.recognize/cap.flowchart/cap.control/skillMarket 标准能力；flowchart.json 加 $schema/skillId/entry/metadata；新增 USAGE/DEBUG/UPGRADE 三份文档；新增 lifecycle/debug 导出
- 5.x: 旧版 action 兼容层（CDP 检测 / 页面读取 / 页面操作 / 条件回复）
