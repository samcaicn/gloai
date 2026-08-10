# 御批模式 详细设计

> 任务ID：JJC-20260303-001 · 第一批 · 功能1

## 1. 概述

在门下省审议通过后，任务不再直接派发尚书省，而是进入 `AwaitingImperial` 状态，等待用户（皇上）在看板中手动准奏或封驳。

## 2. 状态机变更

```
现有流程：
  DoorReviewing → Approved → ShangShuDispatching

新增流程：
  DoorReviewing → Approved → AwaitingImperial → ImperialApproved → ShangShuDispatching
                                               → ImperialRejected → (终止或退回中书)
```

新增状态枚举值：
- `AwaitingImperial` — 待御批
- `ImperialApproved` — 御批准奏
- `ImperialRejected` — 御批封驳

## 3. API 设计

### POST /api/tasks/{id}/imperial-review

```json
// Request
{
  "action": "approve" | "reject",
  "comment": "optional string"
}

// Response 200
{
  "task_id": "JJC-xxx",
  "status": "ImperialApproved" | "ImperialRejected",
  "reviewed_at": "2026-03-03T12:00:00Z"
}
```

### GET /api/tasks?status=AwaitingImperial

返回所有待御批任务列表。

### 配置项

在 `server.py` 或配置文件中新增：

```python
IMPERIAL_REVIEW_CONFIG = {
    "enabled": True,                          # 是否启用御批模式
    "timeout_hours": 24,                      # 超时时间
    "timeout_action": "remind",               # "auto_approve" | "remind" | "block"
    "notify_channels": ["feishu", "telegram"] # 通知渠道
}
```

## 4. 前端组件

### ImperialReviewPanel（新组件）

位置：`edict/frontend/src/components/ImperialReviewPanel.tsx`

功能：
- 显示待御批任务列表（卡片形式）
- 每个卡片含：任务标题、中书省方案摘要、门下省审议意见、等待时长
- 操作按钮：「准奏」（绿色）/ 「封驳」（红色）
- 封驳时弹出评论输入框
- 超时任务高亮提示

在 EdictBoard 中新增 Tab 或面板位。

### 状态流转动画

准奏时：玉玺盖章动画（可选，低优先级）

## 5. 通知集成

门下省审议通过 → 触发通知：

```
📜 新旨意待御批
标题：{task.title}
门下省意见：{review.summary}
⏰ 请在 {timeout_hours}h 内批示
👉 点击查看：{dashboard_url}
```

超时提醒（若 `timeout_action = "remind"`）：

```
⚠️ 旨意超时未批
标题：{task.title}
已等待：{hours}h
👉 请尽快批示
```

## 6. 后端实现要点

- `server.py` 新增路由 `/api/tasks/<id>/imperial-review`
- 任务状态流转逻辑修改：门下省 Approved → 检查 `imperial_review.enabled` → 若开启则进入 `AwaitingImperial`
- 超时检查：启动时注册定时任务（每5分钟检查一次），或使用懒检查（查询时判断）
- 数据存储：在任务 JSON 中新增 `imperial_review` 字段

```python
"imperial_review": {
    "status": "pending" | "approved" | "rejected",
    "submitted_at": "ISO timestamp",
    "reviewed_at": "ISO timestamp",
    "comment": "string",
    "timeout_notified": false
}
```

## 7. 测试用例（刑部）

| # | 场景 | 预期 |
|---|------|------|
| 1 | 门下省通过后任务进入 AwaitingImperial | 状态正确，看板显示 |
| 2 | 点击准奏 | 状态变 ImperialApproved，触发尚书省派发 |
| 3 | 点击封驳（含评论） | 状态变 ImperialRejected，评论保存 |
| 4 | 超时-自动准奏模式 | 超时后自动通过 |
| 5 | 超时-提醒模式 | 超时后发送提醒通知 |
| 6 | 超时-阻塞模式 | 超时后仍保持 AwaitingImperial |
| 7 | 御批模式关闭 | 门下省通过后直接派发（兼容旧流程） |
| 8 | API 参数校验 | 非法 action 返回 400 |
