# 只读 Web 控制台：手机端只读端点（Mika Anywhere P0）

这是「Mika Anywhere」的第一阶段。本文只讲**手机端**：给 Web 控制台用的三个只读接口。浏览器页面和网关会话在另一个变更里；设计与分期讨论见上游 issue #372，网关侧见 PR #374（`tools/relay` 的另一个变更）。

只读——不派工、不回答、不取消，也不碰手机上的任何设置。

## 范围

- **手机新增三个只读端点**：对话列表、一个对话的轮次（分页）、正在运行的任务。
- **不改执行路径**：不新增工具、不放宽审批、不碰付款与接管；回答、派工、取消仍走现有任务 API。
- **网关侧**：`/app` 页面、登录会话和 `/web/api` 只读代理由 `tools/relay` 的另一个变更提供；手机只负责下面的端点。

## 信任边界

```text
浏览器 ──HTTPS/Cookie──> relay 网关 ──WSS（已配对设备通道）──> 手机
                                   │
                          网关内存里保存会话对应的手机令牌
```

- 手机仍然只认 `Authorization: Bearer <手机访问令牌>`，并且**拒绝带 `Origin` 的请求**（防 DNS 重绑定）。网关负责去掉浏览器发来的 `Origin` 和 Cookie，换上网关保存的令牌后转发。
- 网关因此从「只透明转发」变成「会话期间持有手机令牌」；令牌不落盘，网关重启即失效。网关仍然能看到转发的内容（和现有 CLI / MCP 一样，不是端到端加密），用自己的、可信的网关是前提。
- 这三个端点不新增信任判断：它们和现有 `/api/v1` 走同一个 `RemoteAccess`，失败冷却、私有地址限制不变。

## HTTP 契约

认证方式和任务 API 相同（`RemoteAccess`），失败冷却、私有地址限制不变。只读端点挂在 `/api/v1` 下：

### `GET /api/v1/conversations?limit=20`

`limit` 1–50，默认 20。最近的在前；没有内容的对话不列。

```json
{
  "conversations": [
    { "conversation": "8f…", "title": "订一张高铁票", "updated_at_ms": 1762400000000,
      "pinned_at_ms": 1762400001000, "turn_count": 6 }
  ]
}
```

### `GET /api/v1/conversations/{id}?from=0&limit=10`

- `limit` 1–10，默认 8；`from` 不传时返回最后一段（打开对话先看到最新的几轮）。
- 返回的 `from` 是实际起点，`total` 是总轮数；往前翻用 `from = 当前 from − limit`。
- 字段超长会截断并以 `truncated` 标出：用户原话 2,000 字、回答 4,000 字、备注 500 字、单条插话 500 字，插话最多 10 条。截断按码点，不会切出半个 emoji。
- 一页最多 10 轮、单轮各项合计约 35 KB（按 CJK 3 字节/字符估算），整页留在 relay 单帧 1 MiB 以内。

```json
{
  "conversation": "8f…", "title": "订一张高铁票", "total": 6, "from": 4,
  "turns": [
    { "index": 4, "user_text": "帮我改签到下午", "outcome": "改好了…", "status": "succeeded",
      "run_id": "…", "steers": ["顺便选靠窗"], "truncated": false }
  ]
}
```

`status` 与对话文件里的一致：`succeeded` / `unverified` / `failed` / `cancelled`。

### `GET /api/v1/running`

此刻在跑、排队、等用户的任务，**包括手机上发起的**。`status` 沿用任务 API 的说法（`running`、`queued`、`paused`、`waiting_for_answer`、`waiting_on_phone`）；`needs_you` 表示要用户在场处理（回答、确认、接管）。目标 1,000 字、进度 500 字，超长标 `truncated`；最多 20 条。

```json
{
  "running": [
    { "conversation": "8f…", "goal": "整理昨天会议的待办", "status": "running", "run_id": "…",
      "step": 4, "progress": "正在读会议记录", "needs_you": false, "truncated": false }
  ]
}
```

`/api/v1` 的 `capabilities` 会多出 `conversations`、`running`。

## 网关侧

浏览器页面、登录会话和 `/web/api` 只读代理在 `tools/relay` 的另一个变更里（PR #374）。要点：浏览器用同一手机访问令牌登录一次，令牌只存网关内存；只放行这几个只读路径且只允许 GET；手机 `/api/v1` 的 `capabilities` 里出现 `conversations`、`running` 时控制台才启用，否则提示手机端升级。

## 安全红线（本阶段及以后）

- 付款确认、接管仍只能在手机上；远程运行一律按询问开工。
- 模型 Key、OAuth 令牌、锁屏 PIN、ADB 钥匙永不下发到浏览器。
- Web 会话不能新开或扩大手机能力（无障碍、无线调试、Root、沙盒联网、应用访问名单）。
- 控制台只能读，不能经这些端点派工、回答、插话或取消。

## 已知限制

- 只读：不能发消息、派工、回答、插话、取消，也不能改设置。
- 没有屏幕画面、文件、文件卡片、运行过程、定时任务、记忆/技能设置。
- 对话内容按原样读：用户当时发的屏幕标记、附件行等仍在文本里；每轮做了截断（见上面的上限），完整内容仍只在手机上。
- 没有流式输出；调用方看到的回答只在写入对话后出现。
- 列表按对话文件的修改时间排序；还没有搜索、置顶优先排序或按任务筛选。

## 验证

```sh
# Kotlin 只读 API 与 App 读取
./gradlew :core:test --tests "com.wangzan101.mika.core.remote.CompanionApiTest" \
  :app:testDebugUnitTest --tests "com.wangzan101.mika.chat.RemoteConversationReaderTest"

# 编译
./gradlew :app:compileDebugKotlin
```

## 下一步

P1 要让浏览器发消息/派工、插话、回答、取消：除了网关的受控 POST 白名单和 CSRF，还要先决定任务 API 怎么操作手机上的普通对话——现有远程入口只认 `mcp-` 前缀的对话，不能直接写进手机上的对话。屏幕预览、文件分块、SSE 再往后。
