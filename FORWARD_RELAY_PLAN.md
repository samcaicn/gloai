# WeAuto「固定转发」功能设计与开发计划

> 需求（**v2，当前实现**）：后台配置若干**转发对**，每对含**甲方**（`party_a`）与**乙方**（`party_b`），
> **双方都可填多个人/群（多对多）**；消息出现在甲方任一会话就转发给乙方全体，反之亦然，**对称双向**。
> 路由**只看会话归属、绝不解析消息内容**（无 `#姓名`、无引用解析、无焦点记忆）。
>
> > ⚠️ v1（单目标 `sources`→`target` + 内容式回程路由）已于 2026-09-26 被 v2 取代。
> > 本文 §1/§4/§5 中描述 v1 的段落保留作历史记录，实际实现以 **§13（v2 模型）** 为准。
>
> 版本基线：WeChatBot_WXAUTO_SE-3.25.1（微信 4.x 引擎 `wechat_compat` / wechatauto）

---

## 1. 需求定义与术语

### 1.1 v2 术语（当前实现）

| 术语 | 含义 |
|---|---|
| 甲方（`party_a`） | 转发对的一侧，逗号分隔的会话名列表（好友备注名 / 群名），**可多人多群** |
| 乙方（`party_b`） | 转发对的另一侧，同样是会话名列表 |
| 转发对（rule） | 一组 {甲方, 乙方, 行为开关} 的双向绑定关系 |
| 正向 `a_to_b` | 甲方任一会话来消息 → 转发给**乙方全体** |
| 回程 `b_to_a` | 乙方任一会话来消息 → 转发给**甲方全体**（`bidirectional` 为真时） |
| 来源标签 | 每条转发自动加 `[来自 张三]` / `[来自 群名-成员]`，接收方知道是谁说的 |

**核心语义（v2）**：
- 路由**只看 `who`（会话名）落在哪一侧**，完全不解析消息内容；
- 多对多：甲方 3 人、乙方 2 人时，甲任一人发言 → 乙 2 人全收；乙任一人发言 → 甲 3 人全收；
- 甲、乙**不能重叠**（重叠则方向不确定，规则标 `invalid` 并停用，防回环与重复）；
- 转发默认**不触发 AI 回复**（AI 不插嘴），除非规则开启 `ai_reply_to_source`；
- `bidirectional=false` 可退化为单向（只转 甲方→乙方，乙方消息放行给 AI 原流程）。

### 1.2 v1 术语（已废弃，保留作历史）

| 术语 | 含义 |
|---|---|
| 源（source） | 被转发的会话：好友昵称 / 群名（`xxx@chatroom`） |
| 目标（target） | 收转发的人（例：李四），一个规则只有一个目标 |
| 焦点（focus） | 目标当前"正在对话"的源，决定回程投递给谁 |

v1 的核心语义：源的消息**不再进入 AI 回复链路**，回程靠「`#姓名` > 引用 > 焦点 > 单源兜底」五级优先级解析内容决定投递给谁。

---

## 2. 现状代码锚点（改动落点）

| 位置 | 说明 | 影响 |
|---|---|---|
| `bot.py:1677 message_listener` / `1690 _message_listener_impl` | 唯一消息入口（回调式） | **转发钩子挂这里** |
| `bot.py:1719-1728` `msgattr == 'self'` 分支 | bot 自己发的消息直接 `return` | **天然防回环**，转发出去的消息不会二次触发 |
| `bot.py:1742-1826` | 消息类型归一化（voice→文本、link→URL、quote→引用体、merge→多行文本） | 钩子必须放在**之后**，才能拿到可读文本 |
| `bot.py:1832` `should_process_this_message` | AI 触发判定（@/关键词/监听列表） | 钩子必须放在**之前**，才能"只转发不 AI 回复" |
| `bot.py:5167-5176` `for user_name in user_names: AddListenChat` | 只对监听列表注册监听 | 转发源/目标需**额外注册监听**，否则收不到 |
| `wechat_compat.py:469-479` | `SendMsg(msg, who)` / `SendFiles` / `SendImage` | 转发出口（侧栏搜索发送，无需 ChatWith） |
| `bot.py:5137-5162` | `wx.SendMsg` 被包装为 `_auto_add_sendmsg` | 转发**必须走 `wx.SendMsg`**（不要绕过），以复用"发送即互动"登记 + bot 回声剔除（防污染风格学习） |
| `bot.py:1538 persist_listen_list` / `5007 save_user_timers` | 原子写盘范式（`.tmp` + `os.replace`） | 规则/状态持久化照抄此范式 |
| `config_editor.py:4520 style_lab` / `templates/style_lab.html` | 最近新增页面的完整样板 | WebUI 页面照抄 |
| `templates/config_editor.html:3668` | 侧栏 "风格模仿" 入口 | 新增 "固定转发" 入口 |

**关键限制（实测代码得出，非猜测）**

1. **无原生转发 API**：wechatauto 未暴露 forward，`Msg`（`wechat_compat.py:66-113`）也无 `download()`/`capture()`。媒体消息 content 仅剩占位符（`[图片]`/`[视频]`…）。→ 一期只能做**内容复制式转发**；媒体降级为占位文本。
2. **昵称必须唯一可解析**：`_resolve_username` / `quick_send` 走侧栏搜索发送，**重名/相似名可能发错人**。→ UI 强提示用"备注名"，启动时做存在性校验。
3. **`get_dynamic_config`（bot.py:175）只解析单行字面量**，多行 JSON 规则不能塞进 `config.py`。→ 规则走独立 `forward_rules.json`。

---

## 3. 总体架构

```
微信消息 ──► message_listener ──► _message_listener_impl
                                        │
                        (self 消息 return → 防回环)
                                        │
                        类型归一化（voice/link/quote/merge）
                                        │
                    ┌───────────────────▼────────────────────┐
                    │  forward_hub.try_handle_incoming(...)  │  ← 唯一新增钩子
                    │   ① 规则匹配（源→目标 / 目标→源）      │
                    │   ② 回程路由（焦点 / 引用 / #显式指令）│
                    │   ③ 去重 + 限流                        │
                    │   ④ 组装文本（来源标签）               │
                    │   ⑤ wx.SendMsg 投递                    │
                    │   ⑥ 状态 + 流水落盘                    │
                    └───────────────────┬────────────────────┘
                            命中并消费 ──┴── 未命中 ──► 原有 AI 链路（不变）
```

**模块划分**：新建 `forward_hub.py`（规则/路由/投递/日志，零 bot 依赖），`bot.py` 只加 2 处钩子（初始化注入引擎 + 消息拦截），`config_editor.py` 加页面与 API。bot.py 净增约 15 行，风险最低。

**依赖注入**：`forward_hub.attach(wx, logger)` 由 `main()` 在 `wx` 初始化后调用，避免循环导入。

---

## 4. 配置模型：`forward_rules.json`（项目根目录）

```json
{
  "enabled": true,
  "log_enabled": true,
  "max_log_entries": 500,
  "rate_limit": { "window_sec": 10, "max_msgs": 15 },
  "rules": [
    {
      "id": "r1",
      "name": "客服转接",
      "enabled": true,
      "sources": ["张三", "客户群A"],
      "target": "李四",
      "bidirectional": true,
      "source_label": true,
      "label_format": "[来自 {src}{member}]",
      "ai_reply_to_source": false,
      "group_need_at": false,
      "group_keywords": [],
      "media_mode": "placeholder",
      "respect_quiet_time": false
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `sources` / `target` | 会话名（好友昵称或群名），需与微信侧栏可搜索名一致 |
| `bidirectional` | 开启回程；`false` = 单向广播 |
| `source_label` | 转发给目标时是否加来源前缀（回程路由的依据之一） |
| `ai_reply_to_source` | 转发后是否仍让 AI 自动回复源（默认 `false`） |
| `group_need_at` / `group_keywords` | 群作为源时的过滤，防刷屏 |
| `media_mode` | `placeholder`（转 `[图片]` 文本）/ `skip`（丢弃）/ `file`（二期，见 §8） |
| `respect_quiet_time` | 是否受 `QUIET_TIME_*` 静默期限制（默认关：转发是管道，不是主动聊天） |

**热加载**：按 mtime 缓存（60s 内不重读），WebUI 改完规则无需重启 bot。

---

## 5. 回程路由算法（核心难点）— ⚠️ v1 设计，v2 已废弃

> **v2 变更说明**：v1 的回程靠解析消息内容（`#姓名` / 引用体 / 焦点记忆）决定投递给谁。
> v2 改为**对称双向**：乙方任一会话来消息，直接转发给**甲方全体**——不需要也不做任何内容判断。
> 本章保留作 v1 历史记录；v2 的路由算法见 **§13.2**。

李四发消息时，按**优先级**决定投递给谁：

| 优先级 | 判定 | 路由 | 说明 |
|---|---|---|---|
| 1 | `#张三 内容` 或 `#群名 内容` 显式前缀 | 去掉前缀 → 投给 `张三` | 显式切换焦点，最可靠（避开 `/` 指令通道） |
| 2 | `msgtype == 'quote'` 且引用体含来源标签 | 解析出源 → 投递 | 李四**引用**转发消息回复时精确路由 |
| 3 | 存在焦点 `focus[target] = last_source` 且未过期（默认 24h） | 投给 `last_source` | 主路径："谁最后发来就回给谁" |
| 4 | 规则只有一个源 | 投给该源 | 兜底 |
| 5 | 以上都不成立 | **不转发**，回一条提示："当前没有待回复的会话，请用 `#姓名 内容` 指定" | 绝不乱发 |

**焦点更新时机**：每成功转发一条 源→目标，`focus[目标] = 源`，并写盘 `forward_state.json`：

```json
{ "李四": { "last_source": "张三", "updated_at": 1760000000, "recent": ["张三", "客户群A"] } }
```

- 进程重启后焦点从文件恢复（避免"李四回了但不知道回给谁"）。
- `recent` 保留最近 5 个源，UI 可展示"当前对话对象"。

---

## 6. 关键实现点

### 6.1 正向转发（源 → 目标）

1. 命中规则 → 取正文（已归一化的 `original_content`）。
2. 群聊源：正文前缀 `label_format.format(src=群名, member=发送者)`，私聊源：`[来自 张三]`。
3. 超长按 `MAX_SINGLE_MSG_LEN`(800) 分段（`bot.py:2872 _chunk_by_len` 可复用）。
4. `wx.SendMsg(text, 目标)`；失败重试 2 次，仍失败写 `forward_log` 并 `logger.error`，**绝不抛异常到消息循环**。

### 6.2 防回环 / 去重 / 限流

| 机制 | 实现 |
|---|---|
| 回环 | 依赖 `msgattr=='self'` 早退；另加 `inflight` 集合：`hash(src+dst+content)` 5s 窗口内重复直接丢弃 |
| 自我转发 | 规则校验时拒绝 `sources`/`target` 含 `ROBOT_WX_NAME`（bot.py:615） |
| 限流 | 令牌桶 per `(src→dst)`，`window_sec/max_msgs` 可配；超限丢弃并告警 |
| 异常隔离 | 整个转发函数包 try/except，异常只记日志，**必须放行到原 AI 链路还是丢弃？** → 默认丢弃并记录（避免"转发失败还被 AI 抢答"），可在规则里配 `fallback_to_ai` |

### 6.3 消息类型支持矩阵（一期）

| 类型 | `msgtype` | 一期行为 |
|---|---|---|
| 文本 | `text` | ✅ 原样转发 |
| 语音 | `voice` | ✅ 转 `[语音] 文本`（`msg.to_text()`）；无文本则 `[语音]` |
| 链接卡片 | `link` | ✅ `[链接] url` |
| 引用 | `quote` | ✅ `[引用 <原文>] 内容` |
| 合并转发 | `merge` | ✅ 展开为多行文本 |
| 图片/视频/文件/表情 | `image`/`video`/`file`/`emotion` | ⚠️ 占位文本（引擎未暴露二进制） |
| 拍一拍/系统 | `tickle`/`sys` | ❌ 不转发（现有分支已早退或标记） |

### 6.4 监听注册

`main()` 中 `for user_name in user_names: AddListenChat` 之后追加：

```python
forward_hub.attach(wx, logger)
for who in forward_hub.all_chats():        # 所有规则的 sources + target 去重
    if who and who != ROBOT_WX_NAME and who not in user_names:
        try:
            wx.AddListenChat(nickname=who, callback=message_listener)
        except Exception as e:
            logger.warning(f"固定转发：监听 {who} 失败（该会话可能不存在）: {e}")
```

> **不写入 `LISTEN_LIST`**（避免触发 prompt 文件校验、避免被 AI 主动聊天命中）。`AUTO_ADD_USERS` 开启时 `SendMsg` 包装器仍可能把目标写入列表——属既有行为，UI 里说明即可。

### 6.5 日志

`forward_log.json`（环形队列，默认 500 条）：时间 / 方向 / 源 / 目标 / 原文摘要 / 结果（ok|skip|error）。WebUI 与 `weauto_bot.log` 双写（后者走 `logger.info` 便于排障）。

---

## 7. WebUI 设计

| 项 | 内容 |
|---|---|
| 页面 | `GET /forward`（模板 `templates/forward.html`，复用 `_modern_ui.html` 组件与深色模式） |
| 规则管理 | 列表 + 新增/编辑/启停/删除；源支持多选（文本框逗号分隔，不做复杂选择器） |
| 状态卡 | 显示每个目标的"当前对话对象（焦点）"+ 手动清空焦点按钮 |
| 流水 | 最近 50 条转发记录（时间/方向/源→目标/摘要/结果） |
| 连通性自检 | 按钮：用 `wx.GetAllSubWindow()` 校验规则里的名字是否可解析（**需 bot 侧执行**→ 一期降级为启动日志校验 + 页面提示"请查看 bot 日志确认监听注册成功"） |
| API | `GET/POST /api/forward/rules`、`GET /api/forward/state`、`GET /api/forward/log`、`POST /api/forward/clear_focus` |
| 入口 | `templates/config_editor.html:3668` 附近加 `<a href="{{ url_for('forward_page') }}" class="nav-button">固定转发</a>` |

写盘一律 `.tmp` + `os.replace`（与 `save_user_timers` 一致），并对规则做 schema 校验（类型/空值/自转发/重名目标）。

---

## 8. 分阶段开发计划

### P1 — 转发引擎（核心，必须做）

| # | 任务 | 文件 | 预估 |
|---|---|---|---|
| 1.1 | 新建 `forward_hub.py`：规则加载(mtime 缓存) + 匹配 + 焦点管理 + 去重限流 + 投递 + 日志 | 新增 ~380 行 | 3h |
| 1.2 | `bot.py` 消息钩子（归一化之后、`should_process` 之前） | `bot.py` +8 行 | 0.5h |
| 1.3 | `main()` 注入引擎 + 注册监听 | `bot.py` +10 行 | 0.5h |
| 1.4 | `forward_rules.json` / `forward_state.json` / `forward_log.json` 初始化与原子写 | 新增 | 含在 1.1 |
| 1.5 | 单测：规则匹配、回程优先级 1-5、去重、限流、状态恢复 | `tests/test_forward_hub.py` | 1.5h |

**验收**：两个真实微信账号，源↔李四双向通；源侧 AI 不自动回复；重启后焦点保持；连续 20 条不丢不乱序。

### P2 — WebUI 规则管理（必须做）

| # | 任务 | 文件 | 预估 |
|---|---|---|---|
| 2.1 | `/forward` 页面 + `/api/forward/*` 4 个端点 | `config_editor.py` +120 行 | 2h |
| 2.2 | `templates/forward.html`（沿用 style_lab 样板 + 深色模式） | 新增 ~260 行 | 2h |
| 2.3 | 侧栏入口 + 规则 schema 校验/错误提示 | `config_editor.html` +5 行 | 0.5h |

**验收**：页面增删改规则后 60s 内 bot 生效（或立即：写盘时同步 touch 触发重读）；改坏 JSON 不崩溃。

### P3 — 增强（可选）

| 任务 | 说明 |
|---|---|
| 群聊回程 @成员 | `wechat_compat` 无 at 支持，降级为正文写 `@张三` |
| 媒体原件转发 | 需在 `_parse` 保留原始路径并给 `Msg` 加 `download()`，再走 `SendFiles/SendImage`；引擎侧可行性需先验证 |
| `quiet hours`、关键词白名单、多目标广播（1 源 → N 目标） | 配置层扩展 |
| CLI / MCP | `cli.py`、`weauto_mcp.py` 增加 forward 子命令，便于脚本化 |

### P4 — 打包与联调（发布前）

| 任务 | 说明 |
|---|---|
| PyInstaller | `forward_hub.py` 需在 hidden-import（或 `--hidden-import forward_hub`）；`forward_*.json` 若为运行时生成则无需 datas |
| 单 EXE 验证 | `WeAuto.exe --bot` 实测一轮转发；注意**同时只能 1 个 bot 占微信监听**（WebUI 启动的 bot 与手动 `bot.py` 二选一） |
| 发布 | push 到 `github(samcaicn/gloai)` 的 `weauto` 分支触发 CI（**需 `dangerouslyDisableSandbox: true`**） |

---

## 9. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 会话名解析失败（重名/改名/未加好友） | 转发静默失败 | 启动注册监听时逐个校验并 `logger.warning`；UI 提示填"备注名"；失败记流水 |
| 群全量转发刷屏 | 目标被淹没 | 默认 `group_need_at=false` 但**UI 默认勾选关键词过滤空为提醒**；加限流 15 条/10s |
| 焦点串线（多源并发） | 李四回错人 | 引用回复 + `#姓名` 显式前缀两条精确通道；焦点 24h 过期 |
| bot 自动回复抢答 | 源收到 AI 回复 | 钩子在 `should_process` 之前拦截并 `return` |
| 打包漏文件 | EXE 启动报 ModuleNotFound | P4 增加 hidden-import 并实测 |
| 与"风格学习"互相污染 | bot 转发的话被当主人语料 | 走 `wx.SendMsg` 包装器 → `record_outgoing_bot` 已登记，回声剔除生效 |

---

## 10. 验收测试用例

| # | 场景 | 期望 |
|---|---|---|
| T1 | 张三发"你好" | 李四收到 `[来自 张三] 你好`；张三**不**收到 AI 回复 |
| T2 | 李四回"收到" | 张三收到"收到"（无前缀） |
| T3 | 群 A 的小王发言 | 李四收到 `[来自 客户群A-小王] …` |
| T4 | 李四回复后，张三又发消息 | 焦点切回张三，李四再回 → 张三收到 |
| T5 | 李四用引用回复张三那条 | 精确路由到张三（即使焦点已变） |
| T6 | 李四发 `#客户群A 大家好` | 群 A 收到"大家好" |
| T7 | 无焦点时李四发消息 | 李四收到提示"请用 #姓名 指定"，消息不乱发 |
| T8 | 张三连发 30 条 | 限流生效（≥15 条/10s 后丢弃并告警），顺序不乱 |
| T9 | bot 重启 | 焦点从 `forward_state.json` 恢复，李四回复仍正确 |
| T10 | 规则填自己昵称 / 源=目标 | 启动校验拒绝并告警 |
| T11 | `forward_rules.json` 写坏 | 加载失败回落上一版/空规则，bot 不崩溃 |
| T12 | 单 EXE（`--bot`） | 一轮转发正常，无 ModuleNotFound |

---

## 11. 交付物清单

- `WeChatBot_WXAUTO_SE-3.25.1/forward_hub.py`（新增）
- `WeChatBot_WXAUTO_SE-3.25.1/bot.py`（2 处钩子，净增约 18 行）
- `WeChatBot_WXAUTO_SE-3.25.1/config_editor.py`（页面 + API）
- `WeChatBot_WXAUTO_SE-3.25.1/templates/forward.html`（新增）
- `WeChatBot_WXAUTO_SE-3.25.1/templates/config_editor.html`（导航入口）
- 运行时数据：`forward_rules.json` / `forward_state.json` / `forward_log.json`
- 本文档 + `tests/test_forward_hub.py`

---

## 12. 实现状态（截至 2026-09-25）

**已完成：P1（引擎）+ P2（WebUI）。P3 / P4 待办。**

| 模块 | 文件 | 落点 | 状态 |
|---|---|---|---|
| 转发引擎 | `forward_hub.py`（新增，~560 行，零 bot/微信依赖） | 规则热加载 / 归一化 / 正向 / 回程 / 去重限流 / 状态流水 | ✅ |
| 消息钩子 | `bot.py` ~1719（chat_history 之后、`tickle` 判定之前） | `forward_hub.handle_incoming(...)` | ✅ |
| 引擎挂载 + 监听注册 | `bot.py` `main()` ~5196 | `forward_hub.attach(wx, logger, robot_name=ROBOT_WX_NAME)` + `all_chats()` 额外 `AddListenChat` | ✅ |
| 顶部容错导入 | `bot.py` ~88 | `try: import forward_hub except: forward_hub = None` | ✅ |
| WebUI 页面 | `templates/forward.html`（新增） | 规则 CRUD / 焦点卡 / 流水 / 全局开关限流 | ✅ |
| WebUI 路由 | `config_editor.py` ~4853 | `/forward` + 5 个 `/api/forward/*` | ✅ |
| 侧栏入口 | `templates/config_editor.html` ~3669 | 「固定转发」导航按钮 | ✅ |
| 单测 | `tests/test_forward_hub.py`（新增，19 用例） | 覆盖回程优先级 1-5 / 去重 / 限流 / 焦点恢复 / 媒体 / 自转发拒绝 / 坏 JSON 容错 | ✅ **19/19 通过** |

**实现中对原计划的两处关键修正（均已实测代码确认）：**
1. **钩子位置**：原计划插在「归一化之后、`should_process` 之前（~1832）」，但实测群消息在 `bot.py:1737` 就 `return`，根本到不了 1832。改为插在 **chat_history 记录之后、`if msgattr=='tickle'` 之前**，并让引擎**内部自行做消息归一化**（直接吃原始 `msg` 对象），从而同时覆盖好友与群源。
2. **自转发处理**：原计划「启动校验拒绝」改为「保存但 `invalid` 标记 + UI 红徽标提示」，避免整包配置写盘失败（更符合 WebUI 交互）。

**打包（P4）结论**：`bot.py` 顶部为静态 `import forward_hub`，PyInstaller 自动收集；`forward.html` 位于 `templates/`，由冻结引导逻辑整目录拷贝——无需改 spec、无需额外 `--hidden-import`。

**遗留 / 待 P3**：媒体原件转发（`wechat_compat._parse` 需保留原始路径并给 `Msg` 加 `download()`）、`quiet hours` 实装、CLI/MCP 子命令。

---

## 13. v2：多对多 / 对称双向模型（2026-09-26 重构，当前实现）

### 13.1 配置模型 `forward_rules.json`

```jsonc
{
  "enabled": true,
  "log_enabled": true,
  "max_log_entries": 500,
  "rate_limit": { "window_sec": 10, "max_msgs": 15 },
  "rules": [
    {
      "id": "r1",
      "name": "项目A对接",
      "enabled": true,
      // 甲方 / 乙方：每行一个成员，可写 "会话名" 或 "会话名=称呼"（详见 §15）
      "party_a": ["张三=张总", "王五", "客户群A@chatroom=A群"],
      "party_b": ["李四=李经理", "赵六"],
      // 归一化落盘后统一为对象形式（UI 与 API 两种写法都接受）：
      //   [{"name": "张三", "alias": "张总"}, {"name": "王五", "alias": ""}]
      "bidirectional": true,          // false = 只转 甲方->乙方
      "source_label": true,
      "replace_alias": true,          // 转发前把标签与正文里的本名替换为「称呼」
      "label_format": "[来自 {src}{member}]",
      "ai_reply_to_source": false,    // true = 转发的同时也让 AI 回复源侧
      "group_keywords": [],           // 空=不过滤（可选的内容过滤开关，与路由无关）
      "media_mode": "placeholder",    // placeholder | skip
      "respect_quiet_time": false,
      "invalid": []                   // 引擎回填：校验问题列表，非空则该规则自动停用
    }
  ]
}
```

**校验规则**（写盘时软校验：保存成功，但问题项标 `invalid` 并自动停用该规则，UI 红徽标提示）：
- 甲方 / 乙方为空 → invalid
- 任一侧含机器人自己（`ROBOT_WX_NAME`）→ invalid
- 甲、乙**重叠** → invalid（方向不确定，防回环/重复投递）

### 13.2 路由算法（`forward_hub.handle_incoming`）

```
msgattr in (self, tickle)          -> return False（自己发的/拍一拍，绝不过问，防回环）
全局 enabled == false              -> return False
对每条 enabled 规则：
    who 属于甲方(且不属于乙方) -> kind=a_to_b, dests=乙方全体(剔除 who、机器人)
    who 属于乙方(且不属于甲方) -> kind=b_to_a, dests=甲方全体(剔除 who、机器人)
    同时属于甲、乙              -> 跳过该规则（方向不确定）
    kind==b_to_a 且 bidirectional==false -> 跳过（单向模式）
无规则命中                          -> return False（放行 AI 原流程）
命中：
    _deliver(): 归一化 -> 媒体占位/skip -> 群关键词过滤 -> 加来源标签
                -> 逐目标 去重(5s) -> 限流(令牌桶) -> wx.SendMsg
    return not ai_reply_to_source   // True=抑制 AI；True 表示"已消费"
```

关键点：
- **不解析内容决定路由**——只按 `who`（会话名）归边；
- `self` 消息天然被忽略，加上甲/乙不可重叠 + `dests` 剔除 `who`，三重防回环；
- 去重键 `(who, dst, content)`，限流桶 `(who, dst)`，因此多目标 mesh 下不同目标互不误杀。

### 13.3 本次改动文件

| 文件 | 改动 |
|---|---|
| `forward_hub.py` | 重写为 v2（HUB_VERSION 2.0.0）：schema 改 `party_a`/`party_b`；新增 `_direction()` / `_deliver()`；删除 `#姓名`、`SOURCE_LABEL_RE`、焦点状态、`forward_state.json`、`get_state`、`clear_focus` |
| `config_editor.py` | `/forward` 去掉 state 数据；删除 `/api/forward/state`、`/api/forward/clear_focus`（剩 3 个端点：rules GET/POST、log GET） |
| `templates/forward.html` | 规则卡改为「甲方 / 乙方」双 textarea；删除焦点面板；流水方向显示 `甲方→乙方` / `乙方→甲方` |
| `bot.py` | **无需改动**（`handle_incoming` / `attach` / `all_chats` 签名不变）；仅把注释术语改为「甲方/乙方」 |
| `tests/test_forward_hub.py` | 重写 19 条用例：正向/回程、多对多 mesh、群标签、单向抑制、去重、限流、媒体、AI 双发、`all_chats`、重叠/自填 invalid、坏 JSON、归一化 |

### 13.4 测试结论

`python tests/test_forward_hub.py` → **19/19 通过**（媒体转发补齐后为 **26/26**）；
`forward_hub.py` / `config_editor.py` / `bot.py` 均 `py_compile` 通过；`forward.html` Jinja 解析通过。

> 踩坑记录：中文姓名 `sorted()` 按 Unicode 码点排序（`张三` U+5F20 < `王五` U+738B），
> 断言多目标集合时不要用 `sorted(...) == [...]` 硬编码顺序，改用 `set(...)` 顺序无关比较。

---

## 14. 媒体原文件转发（图片 / 视频 / 文件 / 语音）

目标：**不再只发 `[图片]` 占位**，而是把收到的媒体**原样转发**给对端。

### 14.1 能力来源（已核实 vendor 源码，非臆测）

项目内 `vendor/wechatauto` 自带 `MediaDownloader`（`vendor/wechatauto/media.py`），其统一入口：

```python
def download_media(self, user: str, local_id: int, save_dir=None) -> Optional[str]:
    """按消息类型自动分发：3 图片 / 34 语音 / 43 视频 / 49 文件"""
```

只需要两个参数：
- `user` = **会话原始 username**（`media_0.db` 按会话分组，必须是会话 wxid，`demo_forward_voice.py` 注释明确写了这点）
- `local_id` = 消息 ID

而监听回调的 row **本来就有这两个字段**（`db.py:add_listener` 注释 + `_export_row` 实测字段：
`local_id / type / type_code / sender_id / create_time / content / server_id / md5 / sort_seq`）。

### 14.2 缺口与补全

原来的 `wechat_compat._parse()` 在解析媒体消息时**把原始 row 丢弃了**——
只取了 `url / quote_content / messages / voice_text`，`Msg.local_id` 始终为 `None`，
这正是「图片识别静默失效」和「媒体只能占位转发」的共同根因。

补全（`wechat_compat.py`）：

| 改动 | 说明 |
|---|---|
| `Msg.__slots__` 增 `chat_username` / `type_code` | `local_id` / `create_time` 字段原本就有，只是没传值 |
| `_parse()` 构造 Msg 时传入 `_media_keys` | `local_id` + `create_time` + `chat_username`(会话 username) + `type_code`(local_type)；文本消息也一并附带，保持字段一致 |
| `WeChat.DownloadMedia(msg, save_dir, timeout)` | 新增：用 `self._db` + `MediaDownloader` 提取原文件，返回本地路径；失败返回 `None` |

`DownloadMedia` 的三条硬约束：
1. **绝不卡死监听回调** → 下载放进守护线程 + `join(timeout)`（默认 20s），超时即放弃；
2. **串行** → 与 Listener 轮询共用同一个 sqlite 连接，加 `_MEDIA_LOCK` 避免锁竞争；
3. **失败静默降级** → 任何异常一律吞掉返回 `None`，由调用方发占位文本。

### 14.3 转发流程（`forward_hub._deliver`）

```
媒体消息（content is None）
  ├─ media_mode=skip        -> 丢弃，记 skip 流水
  ├─ media_mode=original    -> wx.DownloadMedia(msg)
  │      ├─ 拿到文件 -> 先发来源标签 -> 图片走 SendImage（失败回退 SendFiles），其余走 SendFiles -> ok
  │      └─ 拿不到   -> 降级：发 [图片]/[视频]/[文件] 占位
  └─ media_mode=placeholder -> 直接发占位（不下载）
语音消息（voice）
  └─ original 时：先发「[语音消息]: 转写文本」，再补发原始 SILK 文件 —— 内容与原件都不丢
```

媒体缓存落在 `<base>/forward_media/`，超过 200 个文件按 mtime 清理最旧的，避免长期运行占满磁盘。

### 14.4 消息类型支持矩阵

| 类型 | local_type | 原文件转发 | 降级 |
|---|---|---|---|
| 图片 / 动画表情 | 3 | `download_image` → `SendImage`（失败回退 `SendFiles`） | `[图片]` |
| 语音 | 34 | `download_voice` → `.silk` 以文件发送 + 转写文本 | 仅转写文本 |
| 视频 | 43 | `download_video` → `SendFiles` | `[视频]` |
| 文件 | 49 | `download_file` → `SendFiles`（保留原文件名） | `[文件]` |
| 贴纸 | 47 | 不在 `download_media` 分发内 | `[表情]` |
| 链接 / 卡片 | — | 文本化 `[卡片链接]: url` | — |
| 引用 | — | 文本化（含引用体） | — |
| 合并转发 | — | 文本化逐条展开 | — |
| 位置 / 系统 / 拍一拍 | — | 不转发 | — |

### 14.5 已知边界（如实告知）

- **语音**：微信协议不支持直接转发语音（无转发入口），vendor 的 `demo_forward_voice.py`
  采用的方案就是「提取 SILK → 以**文件**发送」。因此对方收到的是 `.silk` 文件而非可播放的语音条，
  故额外附一条转写文本保证「内容可读」。这是引擎能力的上界，不是实现偷懒。
- **图片解密**：微信 4.x 图片需 AES/XOR 密钥，`MediaDownloader` 会尝试派生或扫描；
  首次可能较慢（受 20s 超时约束）或失败 → 自动降级占位，不会卡住收发。
- **缓存目录**：`forward_media/` 需可写；清理策略为保留最新 200 个文件。

---

---

## 15. 成员称呼 + 消息称呼重写（2026-09-26，v2.3.0）

### 15.1 要解决的问题

消息是**发件人写给某个人**的，所以开头常带称呼：`张总，` / `李经理：` / `二舅 你好`。
转发给对端后，这个称呼有两类问题：

1. **张冠李戴**：称呼用的是发件人视角的叫法，对端未必这么叫（甚至根本不是这个人）
2. **不适用**：称呼的就是接收方本人时，没人会这么称呼自己

所以转发前必须重写称呼：**认得出是谁 → 换成「我（主人）对他的称呼」；认不出 → 直接去掉**。

### 15.2 配置项

**成员称呼**（甲方/乙方列表，每行一条）：

```
张三=张总          # 职位头衔
王五=二舅          # 亲戚关系
李四=李经理
赵六               # 称呼留空 -> 原样（若被认出则删掉称呼语）
```

归一化落盘：`{"name": "张三", "alias": "张总"}`
- `name` = 微信会话名，用于匹配 `who` 与注册 `AddListenChat`
- `alias` = **主人视角**的称呼（职位头衔 / 亲戚关系 / 尊称），由后台逐个配置

**规则级开关**：

| 字段 | 默认 | 含义 |
|---|---|---|
| `salutation_mode` | `auto` | 句首称呼处理：`off` 不处理 / `rule` 只本地规则 / `auto` 规则优先·拿不准才问 AI / `ai` 每条都问 AI |
| `replace_alias` | `true` | 正文其余位置的成员本名 → 「我的称呼」；来源标签也用称呼（`[来自 张总]`）|

### 15.3 识别流程

```
消息正文
  │
  ├─ 无分隔符（"李四直接找我"）        -> 不当称呼，只做正文本名替换
  │
  ├─ 切出句首 head（最长 12 字）
  │    ① head == 问候语（你好/各位/早上好…）        -> 删掉
  │    ② head == 成员 name 或 alias                 -> 命中该成员
  │    ③ head = 姓氏/排行 + 称谓词（张总/李经理/二舅）
  │         -> 去掉称谓词回查成员：
  │            命中成员 -> 换成「我的称呼」（没配称呼则删掉）
  │            查无此人 -> 删掉（认得出是称谓但不知是谁）
  │    ④ 命中的是来源本人（自称）                    -> 不动
  │    ⑤ 都不成立                                    -> 本地没把握
  │
  └─ 本地没把握：
       mode=rule / off        -> 保留原文
       mode=auto 且形态像称呼 -> 问 AI（head 2~4 字 + 分隔标点，且不在寒暄黑名单里）
       mode=ai                -> 直接问 AI
       AI 失败/超时/越界      -> 退回本地结果
```

**称谓词库**分两类，均长词优先匹配：
- 职位/头衔/地位：董事长、总经理、老总、老板、经理、总监、主管、主任、行长、律师、教授、教练、工程师、老师、师傅、总、哥、姐、先生、女士…
- 亲戚/亲昵：爷爷、奶奶、外公、外婆、老爸、老妈、伯伯、叔叔、阿姨、姑姑、舅舅、姨妈、哥哥、姐姐、嫂子、舅、姨、姑、叔…

### 15.4 AI 判断（`salutation_mode` = auto / ai）

- 注入方式：`bot.py` 的 `forward_ai_salutation(text, roster, source)` 挂到 `forward_hub.attach(ai_resolver=...)`
- 引擎侧用**守护线程 + 8s 超时**调用，模型卡住也不会卡住消息回调
- prompt 里给出「成员名单 → 我的称呼」映射，并要求只改开头称呼部分
- **结果安全校验**（防模型胡改）：
  1. 尾部必须与原文一致（公共后缀 ≥ 原文长度的 50%）
  2. 新增前缀 ≤ 15 字
  3. 不满足 → 丢弃 AI 结果，退回本地规则
- **省 token**：`auto` 模式下只有「形态像称呼」才问 AI；head 命中寒暄黑名单（好的/收到/是的/嗯/另外/还有/麻烦…）直接跳过；同一正文 + 同一名单 60s 内结果缓存复用

### 15.5 不处理的部分（刻意保留）

- **媒体文件名**：`report.pdf` 不参与称呼替换
- **流水日志**：`src`/`dst` 记真名，便于排查
- **路由匹配键**：`who` 仍按真实会话名匹配（称呼只影响输出，不影响路由）

### 15.6 改动文件

| 文件 | 改动 |
|---|---|
| `forward_hub.py` | v2.3.0：成员支持 `name`/`alias`；新增称谓词库 `TITLE_WORDS`/`KIN_WORDS`、问候语表、`_head_split`/`_strip_honor`/`_find_member`/`_salutation_local`/`_salutation_ai`/`_process_salutation`/`build_salutation_prompt`；`attach()` 新增 `ai_resolver`；`_deliver` 两段式重写（句首称呼 + 正文本名）；新增结果缓存 |
| `bot.py` | 新增 `forward_ai_salutation()`（调 `call_chat_api_with_retry`，`max_retries=0`）；`attach` 时注入 |
| `templates/forward.html` | 成员 textarea 改「每行 `会话名=我对他的称呼`」；新增「句首称呼处理」下拉；`replace_alias` 改名「正文本名替换为称呼」；说明文案重写 |
| `tests/test_forward_hub.py` | 52/52 通过（新增 16 条称呼用例）|

### 15.7 踩坑记录

- `set()` 不能装 dict：`_normalize_rule` 里算甲乙重叠必须先用 `_member_names()` 转成会话名字符串，
  否则 `unhashable type: 'dict'` 会让 `save_rules` 整包失败（表现为"规则保存后仍不生效"）。
- `_call_with_timeout(fn, *args, **kw)` 的 `timeout` 必须用关键字传，写成位置参数会被塞进 `*args`
  传给回调，导致 `TypeError` 被静默吞掉、AI 结果恒为 None。
- AI 回调签名兼容两参与三参（`TypeError` 回退），避免老回调突然失效。
