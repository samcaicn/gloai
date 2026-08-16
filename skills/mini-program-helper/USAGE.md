# 微信小程序开发助手 — 使用流程

## 搜索与加载

前端 AutomationPage 通过搜索软件名 `微信小程序` / `WeChat DevTools` 发现此技能。

## 操作模式

| Action | 用途 |
|--------|------|
| `create` | 项目搭建：提供项目名即可生成结构 + 首屏代码 |
| `guidance` | 开发指导：咨询组件/API/生命周期/云开发/登录/支付等 |
| `template` | 代码模板：list/form/detail/tabs/login 五套内置模板，或自定义描述让 LLM 生成 |
| `publish` | 发布审核：按阶段提供备案/提审/驳回处理/运营建议 |
| `optimize` | 性能优化：首屏/分包/渲染/启动/Skyline 迁移方案 |
| `troubleshoot` | 问题排查：输入错误现象或错误码，LLM 诊断根因 + 修复代码 |
| `query` | 知识查询：按 topic 关键词直接获取知识条目 |

## 执行示例

```json
// 搭建商城项目
{ "action": "create", "projectName": "我的商城", "appId": "wx1234567890abcdef" }

// 学习云开发
{ "action": "guidance", "topic": "云数据库", "experience": "新手" }

// 生成列表页
{ "action": "template", "pageType": "list" }

// 发布准备
{ "action": "publish", "stage": "准备" }

// 首屏优化
{ "action": "optimize", "focus": "首屏" }

// 排查白屏
{ "action": "troubleshoot", "issue": "真机预览白屏" }
```

## 停止后回看

- `get_flowchart` — 获取流程图结构
- `get_trace` — 获取执行轨迹
- 流程图含 10 节点，追踪每次 LLM 调用的起止和结果

## 依赖

- `cap.llm@^1.0.0` — LLM 问答生成指导内容
- `cap.flowchart@^1.0.0` — 流程图追踪
- `cap.runtime@^1.0.0` — 日志和 sleep
