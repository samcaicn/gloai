---
id: "com.tupautochrome.skills.auto-product-comm"
name: "自动选品智能沟通"
name_en: "Auto Product Communication"
version: "3.0.0"
author: "tupAI"
license: "MIT"
homepage: "https://github.com/your-org/auto-product-comm"
icon: ""

# 分类与搜索
category: "web"
software_names: ["微信小店"]
software_names_en: ["WeChat Store"]
tags: ["auto-product", "weixin-store", "cdp", "browser", "automation", "merchant", "communication", "选品", "沟通", "weui", "flowchart", "筛选", "llm", "multi-turn", "self-evolution", "hermes", "material-management", "human-in-loop"]
keywords: ["自动选品", "联系商家", "微信小店", "选品沟通", "筛选", "cdp自动化", "LLM沟通", "多轮对话", "产品资料", "人工干预", "沟通日志", "自进化", "Hermes"]

# 能力声明
capabilities:
  - id: "main_action"
    name: "自动选品智能沟通 v3.0"
    description: "通过 CDP 控制浏览器打开微信小店选品中心，交互式预配置筛选条件，LLM 智能生成多轮对话，自动发送产品资料，循环联系多家商家，支持人工干预，记录沟通效果，通过 Hermes 自我进化"
    inputs:
      - { name: "keywords", type: "array", items: "string", required: false, description: "选品关键词列表（不传则询问用户）" }
      - { name: "filters", type: "object", required: false, description: "筛选配置 { sort, service[], priceRange{composition,min,max}, monthlySales, positiveRate, shopRating }" }
      - { name: "materialFolder", type: "string", required: false, description: "产品介绍资料文件夹路径" }
      - { name: "maxMerchants", type: "number", default: 5, description: "最多联系商家数量" }
      - { name: "maxConvRounds", type: "number", default: 8, description: "每个商家最大对话轮次" }
      - { name: "replyWaitSecs", type: "number", default: 120, description: "等待商家回复超时秒数" }
      - { name: "commStyle", type: "string", default: "专业友好", description: "沟通风格: 专业友好/热情主动/稳重务实/轻松亲切" }
      - { name: "autoEvolve", type: "boolean", default: true, description: "批次结束后是否自动自进化" }
      - { name: "recognition", type: "array", items: "string", default: ["cdp", "uia", "ocr", "vlm"] }
    outputs:
      - { name: "ok", type: "boolean" }
      - { name: "contacted", type: "number", description: "已联系商家数量" }
      - { name: "trace", type: "array" }
      - { name: "logs", type: "array", description: "沟通日志" }
      - { name: "stats", type: "object", description: "沟通统计" }

# 依赖
runtime:
  engine: "js"
  engineVersion: ">=1.0.0 <2.0.0"
  caps:
    - "cap.cdp@^1.0.0"
    - "cap.recognize@^1.0.0"
    - "cap.control@^1.0.0"
    - "cap.flowchart@^1.0.0"
    - "cap.llm@^1.0.0"
    - "cap.ui@^1.0.0"
    - "cap.storage@^1.0.0"
    - "cap.server@^1.0.0"
  permissions:
    - "http:fetch:*"
    - "storage:readwrite"

# CLI 工具依赖
cli_deps:
  - name: "brave-browser"
    min_version: "1.0"
    install_hint: "macOS: brew install --cask brave-browser | Windows: winget install Brave.Brave"

# 升级策略
distribution:
  channel: "stable"
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

# 自动选品智能沟通 v3.0 (Auto Product Communication)

> 用 LLM 把人从重复沟通中解放出来，实现高质量、自适应、可干预的多商家自动沟通，并通过 Hermes 持续自我进化。

## 核心理念

v3.0 不再是简单的"发一条消息就走"，而是**完整的 LLM 驱动多轮对话引擎**：

- **智能化**：LLM 根据商家商品信息生成个性化开场白，分析商家回复意图，自适应调整沟通策略
- **资料辅助**：用户可选择产品介绍资料文件夹，系统在合适时机自动发送相关资料
- **循环沟通**：自动遍历多个商家，每个商家进行完整的多轮对话
- **人工干预**：随时暂停/修改消息/接管对话/跳过商家，保留人的控制权
- **效果记录**：记录每轮对话、商家反馈、沟通成果，形成完整日志
- **自我进化**：批次结束后通过 Hermes 分析历史数据，优化沟通风格、轮次、时机等参数

## 架构

```
┌─────────────────────────────────────────────────────────┐
│                    主执行循环 (execute)                    │
├──────────┬──────────┬──────────┬──────────┬─────────────┤
│ 配置管理  │ 资料管理  │ 页面操作  │ 对话引擎  │  自进化     │
│          │          │          │          │             │
│ 筛选配置  │ 文件夹   │ Shadow   │ LLM 多轮  │ Hermes 分析  │
│ 沟通风格  │ 读取索引  │ DOM 操作 │ 上下文    │ 策略优化     │
│ 商家数量  │ 摘要生成  │ WeUI 下拉 │ 意图识别  │ 参数更新     │
│          │ 关键词匹配 │ 联系商家  │ 资料发送  │ 云端上报     │
├──────────┴──────────┴──────────┴──────────┴─────────────┤
│              人工干预 (cap.control: 暂停/单步/断点)         │
├───────────────────────────────────────────────────────────┤
│              沟通日志 (CommunicationLogger)                │
├───────────────────────────────────────────────────────────┤
│              能力层 (cap.cdp / cap.llm / cap.ui / ...)     │
└───────────────────────────────────────────────────────────┘
```

## 六大核心模块

### 1. MaterialManager — 产品介绍资料管理

用户选择一个包含产品介绍资料的文件夹，系统自动：
- 读取所有文本文件（txt/md/json/csv/doc）
- 用 LLM 为每份资料生成摘要
- 提取关键词用于匹配
- 在对话过程中根据商品信息匹配最相关的资料
- 在合适时机将资料内容精简后发送给商家

```json
// 设置资料文件夹
{ "action": "set_material_folder", "folder": "/Users/me/Documents/products" }

// 查看已加载的资料
{ "action": "get_materials" }

// 按商品信息查找相关资料
{ "action": "find_materials", "productInfo": "坚果零食大礼包", "keywords": ["坚果"] }
```

### 2. ConversationEngine — LLM 多轮对话引擎

核心对话引擎，管理每个商家的完整对话生命周期：

- **generateOpening**：根据商品信息+关键词+沟通风格生成个性化开场白
- **analyzeReply**：分析商家回复的意图(interested/hesitant/resistant)、情绪、关键信息
- **generateFollowUp**：根据分析结果生成针对性跟进消息，温和引导不施压
- **generateFollowUpReminder**：商家未回复时生成关心的跟进提醒
- **isConversationDone**：LLM 判断对话是否自然结束
- **summarizeConversation**：生成对话总结（正面/中性/负面 + 关键信息）

支持 4 种沟通风格：
| 风格 | 描述 | 适用场景 |
|------|------|----------|
| 专业友好 | 专业但不失友好，像有经验的采购经理 | 通用 |
| 热情主动 | 热情开朗，主动推进话题 | 快消品/日用 |
| 稳重务实 | 沉稳务实，注重数据和专业性 | 高客单价/B端 |
| 轻松亲切 | 像朋友聊天一样自然 | 小商家/个体户 |

### 3. 多商家循环沟通

自动遍历筛选结果中的多个商家：
1. 提取商家商品信息
2. 点击联系商家 → 检测新标签页 → 切换到聊天窗口
3. 生成个性化开场白 → 发送
4. 等待回复 → 分析 → 生成跟进 → 发送（多轮循环）
5. 适时发送产品资料
6. 记录对话日志 → 回到选品页面 → 下一个商家

### 4. 人工干预

随时可以暂停执行，进行人工干预：
- **暂停/恢复**：`{ "action": "pause" }` / `{ "action": "resume" }`
- **单步执行**：`{ "action": "step_once" }`
- **断点**：`{ "action": "add_breakpoint", "nodeId": "send_msg" }`
- **停止**：`{ "action": "stop" }`

暂停时会弹出人工干预菜单：
1. 继续（自动接管下一轮）
2. 修改下一条消息（输入消息内容）
3. 接管（人工继续，跳过自动对话）
4. 跳过此商家

### 5. CommunicationLogger — 沟通日志

记录每次商家沟通的完整信息：
- 商家商品信息
- 完整对话历史（角色+内容+时间戳）
- 对话轮次、持续时间
- 商家情绪变化
- 已发送资料
- 对话总结（正面/中性/负面）
- 关键获得信息

```json
// 查看所有沟通日志和统计
{ "action": "get_logs" }

// 清空日志
{ "action": "clear_logs" }
```

### 6. SelfEvolution — Hermes 自进化

批次结束后自动分析历史沟通数据：
- 统计成功率、平均轮次、正面/负面比例
- 用 LLM 分析成功模式和失败原因
- 提取最佳沟通风格、开场白技巧、跟进策略
- 自动更新配置参数（最大轮次、回复等待、沟通风格）
- 上报到 Hermes 云端用于全局优化

```json
// 手动触发自进化分析
{ "action": "self_evolve" }

// 查看进化历史
{ "action": "get_evolve_history" }
```

## 使用示例

```json
// 1. 完整执行（自动配置 + 多轮对话 + 自进化）
{
  "action": "execute",
  "keywords": ["零食", "坚果"],
  "filters": {
    "sort": "高佣金优先",
    "service": ["7天无理由", "品牌"],
    "priceRange": { "composition": "价格", "min": "10", "max": "500" },
    "monthlySales": "1万以上",
    "positiveRate": "90%以上",
    "shopRating": "4.5以上"
  },
  "materialFolder": "/Users/me/Documents/products",
  "maxMerchants": 5,
  "maxConvRounds": 8,
  "commStyle": "专业友好",
  "autoEvolve": true
}

// 2. 先配置再执行
{ "action": "setup", "keywords": ["坚果"], "commStyle": "热情主动" }
{ "action": "set_material_folder", "folder": "/path/to/materials" }
{ "action": "execute" }

// 3. 查看状态和日志
{ "action": "status" }
{ "action": "get_logs" }
{ "action": "get_evolve_history" }

// 4. 流程控制
{ "action": "pause" }
{ "action": "resume" }
{ "action": "stop" }

// 5. 单步操作（调试）
{ "action": "open_page", "keyword": "坚果" }
{ "action": "extract_merchants" }
{ "action": "generate_opening", "productInfo": "...", "keywords": ["坚果"] }
{ "action": "send_message", "message": "您好..." }

// 6. 回看
{ "action": "get_flowchart" }
{ "action": "get_trace" }
```

## 流程节点

| 节点 id | 类型 | 标签 | 说明 |
|---------|------|------|------|
| `start` | start | 开始 | 流程入口 |
| `ensure` | process | 确保 CDP 连接 | 检测浏览器 CDP 连接 |
| `config?` | decision | 有预配置? | 检查 params/storage |
| `get_config` | io | 交互式获取配置 | 配置关键词+筛选+风格+资料文件夹 |
| `load_materials` | process | 加载产品介绍资料 | 从文件夹读取+索引+摘要 |
| `analyze_cat` | process | LLM 分析分类 | 关键词→分类标签匹配 |
| `navigate` | process | 打开选品页面 | CDP 导航 |
| `wait_page` | process | 等待页面加载 | 等待 Shadow DOM 渲染 |
| `apply_cat` | process | 选择分类标签 | 点击 .tag |
| `apply_filters` | process | 批量应用筛选条件 | WeUI 下拉菜单操作 |
| `wait_results` | process | 等待筛选结果 | 检测联系商家按钮 |
| `extract_merchants` | process | 提取商家列表 | 获取所有商家按钮 |
| `merchant_loop` | process | 商家沟通循环 | 遍历商家 |
| `extract_info` | process | 提取商家/商品信息 | 从商品卡片提取信息 |
| `gen_opening` | process | LLM 生成开场白 | 个性化开场白 |
| `send_msg` | process | 发送消息 | 输入+发送 |
| `wait_reply` | process | 等待商家回复 | 轮询聊天窗口 |
| `reply?` | decision | 商家回复了? | 检测新消息 |
| `analyze_reply` | process | LLM 分析回复 | 意图+情绪+关键信息 |
| `gen_followup` | process | LLM 生成跟进 | 针对性回复 |
| `send_material?` | decision | 需要发资料? | LLM 判断时机 |
| `send_material` | process | 发送产品资料 | 精简后发送 |
| `check_human` | decision | 人工干预? | 检查暂停/断点 |
| `human_intervene` | io | 人工干预/接管 | 用户选择操作 |
| `conv_done?` | decision | 对话结束? | LLM 判断 |
| `log_conv` | process | 记录沟通日志 | 完整记录 |
| `next_merchant?` | decision | 继续下一个? | 未达上限 |
| `batch_report` | process | 生成批次报告 | 统计+LLM 分析 |
| `self_evolve` | process | Hermes 自进化 | 策略优化+参数更新 |
| `end` | end | 结束 | 流程出口 |

## 环境要求

- **Brave 浏览器**：需以 `--remote-debugging-port=9222` 启动
  - macOS: `/Applications/Brave\ Browser.app/Contents/MacOS/Brave\ Browser --remote-debugging-port=9222`
  - Windows: `"C:\Program Files\BraveSoftware\Brave-Browser\Application\brave.exe" --remote-debugging-port=9222`
- **微信小店登录态**：浏览器中需已登录微信小店后台
- **目标页面**: `https://store.weixin.qq.com/talent/pool/home?from=platform&keyword=KEYWORD`
- **页面架构**: micro-app 微前端 (shadowDOM=open)，所有 DOM 查询通过 `shadowRoot`

## Changelog

- **3.0.0**: 重大升级 — LLM 多轮对话引擎、产品资料文件夹管理、多商家循环沟通、人工干预机制、沟通日志记录、Hermes 自进化
- 2.0.0: 重构 — 目标页面改为 talent/pool/home，新增交互式筛选配置、LLM 分类分析、WeUI 下拉菜单操作
- 1.0.0: 初始版本 — CDP 打开选品页面、关键词筛选、联系商家、LLM 沟通文案
