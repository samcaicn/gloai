# 微信结果主动推送 + 本机任务解锁 opt-in Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 Ui 任务加「明确意图时模型可发结果到机主微信」的工具;设置加默认关的「锁屏时运行本机任务」开关,打通 localEnabled 解锁授权链。

**Architecture:** F1 走能力插件模式:core 新 WechatOwnerCapability(端口接口+意图闸门+工具定义),app 侧 AndroidWechatOwnerPort 实现(快照/重校验/Outbox 幂等),TaskRun 按来源+意图门控注入。F2 复用今天已合入的 TaskRelock/守卫体系,只补 localEnabled 配置链(DeviceAccess→DeviceAccessSettings→TaskUnlockPolicy→设置 UI)。

**Tech Stack:** Kotlin,coroutines,Jetpack Compose,既有 Outbox/WechatStore/TaskRelock 底座。

**Spec:** `docs/design/specs/2026-11-21-wechat-owner-push-local-unlock-design.md`
**调研底稿(接口与护栏细节):** 会话内调研笔记(不入库);所需接口与护栏已全部抄入上方 Spec,以 Spec 为准

## Global Constraints

- 通用 AOSP,无品牌特例;代码/注释/文档不出现任何外部项目名称。
- 每个新用户可见字符串必须 11 语言目录全齐:values、values-zh、values-b+zh+Hant、values-ar/es/fa/in/ja/ko/pt/ru/tr。
- 工具说明进 `core/src/main/resources/prompts/tools/`;金样再生成命令必须同时含两个过滤:`./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -PupdateSnapshots`(sizes.txt 归 PromptLayoutTest 所有)。
- 回归命令:`./gradlew :core:test :app:testDebugUnitTest`(禁裸 `:app:test`,OOM)。
- 复用既有 TaskRelock/守卫/恢复链,不得再搬一套回锁实现。
- ToolLabels 新 label 必须过 ToolLabelsTest 全覆盖;新 Risk.Write 工具进 ToolRiskTest 白名单。

## Review Focus

1. 英文明确意图(“send the result to my WeChat”)必须命中闸门、否定句(“不要发我微信”/“don't send to my wechat”)必须不命中——fork 版只有中文模式,本计划修掉(Task 1 测试)。
2. 同一运行内同一段文本第二次发送不得重复投递,返回旧状态(Task 2 发送器测试)。
3. 运行中途换绑/停用微信渠道,发送时必须返回 unavailable 且一次都不发(Task 2 发送器测试)。
4. 唤醒途中用户关掉本机开关,必须在凭据输入前中止(Task 3 DeviceAccess 测试)。
5. 开本机开关不得带动远程开关,反之亦然(Task 3 设置持久化测试)。

---

### Task 1: F1 core——WechatOwnerCapability 与意图闸门

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/cap/wechat/WechatOwnerCapability.kt`
- Create: `core/src/main/resources/prompts/tools/wechat_send_owner.md`
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/tool/AgentTool.kt`(Verb 枚举)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/cap/wechat/WechatOwnerRequestTest.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/cap/wechat/WechatOwnerToolTest.kt`

**Interfaces:**
- Produces(后续任务依赖,签名钉死):
  - `interface WechatOwnerPort { suspend fun send(text: String): WechatOwnerResult }`
  - `data class WechatOwnerResult(val status: Status, val message: String? = null) { enum class Status { Delivered, Uncertain, Failed, Unavailable } }`
  - `fun explicitWechatOwnerRequest(goal: String): Boolean`
  - `class WechatOwnerCapability(private val port: WechatOwnerPort?) : CapabilityProvider`(id 用 `"wechat_owner"`;port 为 null 时不提供工具,sections 也为空)
  - 工具名 `wechat_send_owner`,参数 `text`(1–4000 字),Risk.Write、offScreen、logged
  - `Verb.WechatSendOwner`(AgentTool.kt 枚举加一枚,位置按字母邻近放)

- [ ] **Step 1: 写失败测试 WechatOwnerRequestTest**

断言(中英两套模式 + 否定排除 + 长句不截断):
- `explicitWechatOwnerRequest("把今天的新闻摘要发到我的微信")` == true
- `explicitWechatOwnerRequest("查一下快递进度,结果发我微信")` == true
- `explicitWechatOwnerRequest("summarize my emails and send the result to my WeChat")` == true
- `explicitWechatOwnerRequest("把结果发到我的微信,顺便订个闹钟明早八点提醒我带上伞出门")` == true(长句超过 18 字窗口仍命中)
- `explicitWechatOwnerRequest("不要把结果推送到我的微信")` == false
- `explicitWechatOwnerRequest("don't send anything to my wechat")` == false
- `explicitWechatOwnerRequest("帮我看看微信里有什么新消息")` == false(读微信 ≠ 发到微信)
- `explicitWechatOwnerRequest("今天天气怎么样")` == false

- [ ] **Step 2: 跑 `./gradlew :core:test --tests '*WechatOwnerRequest*' -q` 确认全部编译失败(类不存在)**

- [ ] **Step 3: 实现 WechatOwnerCapability.kt**

三部分,全部自写注释措辞:
1. `WechatOwnerPort` / `WechatOwnerResult`(签名见 Interfaces)。
2. `explicitWechatOwnerRequest(goal)`:中文模式(发送动词组 ×「我的微信/我微信」)+ 英文模式(send/deliver × my wechat),各自带否定前缀排除(不/别/无需/don't/do not/never);**不做字数距离窗口**(修 fork 漏判长句的缺陷)。实现用两组 Regex,模式表是文件级 private val,方便以后扩语言。
3. `WechatOwnerCapability`:sections 在 port 非空且工具被供给时给一段自写的行为守则(只在用户明确要求时用一次;只发最终结果与不确定性;接口接受≠已读;失败/不确定如实报告,绝不自动重发;不许拿微信 App 或浏览器当替身)。execute 调 port.send,按 status 映射 ok true/false,message 直传。

- [ ] **Step 4: 写工具说明 `prompts/tools/wechat_send_owner.md`**

2–3 行英文(与现有工具说明同风格):把最终结果发到机主已绑定的微信私聊;只在用户明确要求时用一次;同文不重复发;delivered 不等于已读,失败如实说。

- [ ] **Step 5: AgentTool.kt 的 Verb 加 `WechatSendOwner`;写 WechatOwnerToolTest 并跑绿**

WechatOwnerToolTest:port 为 null 时 tools() 为空;port 给假实现时 tools() 含 wechat_send_owner 且 Risk.Write;text 超 4000 字被拒;port 返回 Unavailable 时结果是 isError 且消息如实。
跑:`./gradlew :core:test --tests '*WechatOwner*' -q` 全绿。

- [ ] **Step 6: Commit**

`feat(wechat): 机主结果推送能力与显式意图闸门(core)`

### Task 2: F1 app——发送器、绑定查询、TOCTOU 复查、装配

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/wechat/WechatOwnerSender.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/wechat/WechatStore.kt`(尾部加 boundOwnerPeer)
- Modify: `app/src/main/java/com/wangzan101/mika/wechat/WechatChannel.kt`(文本路径发送前补 accepts 复查)
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/assembly/TaskTools.kt`(toolDispatcher 加 wechatOwner 参数 + provider 登记)
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`(工具装配处门控注入)
- Modify: `app/src/main/java/com/wangzan101/mika/chat/ui/ChatViewModel.kt`(ToolLabels +1 行)
- Modify: `app/src/main/res/values*/strings_tools.xml`(11 个目录,各 +1 条)
- Test: `app/src/test/java/com/wangzan101/mika/wechat/WechatOwnerSenderTest.kt`
- Modify: `app/src/test/java/com/wangzan101/mika/wechat/WechatStoreTest.kt`(+1)、ToolRiskTest 白名单

**Interfaces:**
- Consumes: Task 1 的 `WechatOwnerPort`/`WechatOwnerResult`/`explicitWechatOwnerRequest`/`WechatOwnerCapability`。
- Produces: `fun WechatStore.boundOwnerPeer(): WechatPeer?`(四条件:已启用+有账号+机主 contextToken 非空+已有线程;不满足返回 null)。

- [ ] **Step 1: 写失败测试 WechatOwnerSenderTest**

用假 store/假 channel(项目现有测试同款 fake,参考 WechatStoreTest 的写法):
- 正常路径:快照时机主绑定在,发送时仍在 → status Delivered,Outbox 投递 id 形如 `owner-send:<runKey>:` 前缀。
- 换绑失效:快照后把绑定账号换掉 → send 返回 Unavailable,channel 一次都没被调(用计数 fake 断言 0 次)。
- 幂等:同 runKey 同文调两次 send → 第二次返回第一次的状态,channel 只被调 1 次。
- uncertain 不重发:第一次投递结果 Uncertain,再调同文 → 仍 Uncertain,channel 仍 1 次。

- [ ] **Step 2: 跑 `./gradlew :app:testDebugUnitTest --tests '*WechatOwnerSender*' -q` 确认编译失败**

- [ ] **Step 3: 实现**

- `WechatStore.boundOwnerPeer()`(~10 行,语义见 Interfaces)。
- `WechatOwnerSender.kt`:`class AndroidWechatOwnerPort(app: MikaApp, private val runKey: String) : WechatOwnerPort`——构造时快照 expectedAccount+expectedPeer;send 时重读 store 比对(不符 → Unavailable,不发送);投递 id `owner-send:<runKey>:<sha256(threadId+text).take(16)>`;经 outbox.deliver 幂等;Delivered/Uncertain/Failed 按投递结果映射。
- `WechatChannel.kt`:文本发送路径在真正调 API 前的 IO 块内补 `if (!store.accepts(peer, account)) return <失败>`(图片路径已有同款,照抄其判定形态;入口 isAlive 检查保留不动)。

- [ ] **Step 4: 装配与门控**

- `TaskTools.toolDispatcher(...)`:加参数 `wechatOwner: WechatOwnerPort? = null`;providers 列表照现有 object : CapabilityProvider 的挂法加 `WechatOwnerCapability(wechatOwner)`;allTools 追加(参考相邻能力的写法)。
- `TaskRun.kt` 工具装配处:`wechatOwner = if (provenance.origin == RunOrigin.Ui && !conditionCheck && explicitWechatOwnerRequest(task)) AndroidWechatOwnerPort(app, runKey) else null`(runKey 用该运行已有的唯一键,没有就用 conversationId+开始时刻)。
- `ChatViewModel.kt` ToolLabels:`"wechat_send_owner" to R.string.tool_wechat_owner`。
- 11 个 values 目录的 strings_tools.xml 各加 `tool_wechat_owner`(英文默认「发送到我的微信」语义,各语言照现有同文件风格翻译)。

- [ ] **Step 5: 跑测试 + 再生成金样**

```
./gradlew :app:testDebugUnitTest --tests '*Wechat*' -q
./gradlew :core:test --tests '*PromptSurface*' --tests '*PromptLayoutTest*' -PupdateSnapshots -q
./gradlew :core:test -q
```
全绿(ToolRiskTest 若拦新工具,按白名单机制登记)。

- [ ] **Step 6: Commit**

`feat(wechat): 机主结果推送发送器与门控装配(app)`

### Task 3: F2——localEnabled 解锁授权链与设置开关

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/device/DeviceAccess.kt`(store 接口加 localEnabled/beginLocalAttempt;unlock 拆 local 分流)
- Modify: `app/src/main/java/com/wangzan101/mika/settings/DeviceAccessSettings.kt`(config 加字段、setLocalEnabled、beginLocalAttempt、持久化)
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskUnlockPolicy.kt`(加 localUnlockEnabled 参数 + Ui 分支)
- Modify: `app/src/main/java/com/wangzan101/mika/task/TaskRun.kt`(解锁块按来源选 unlockLocal;失败文案三分支)
- Modify: `app/src/main/java/com/wangzan101/mika/device/ui/DeviceAccessSection.kt`(加 localTasks 变体)
- Modify: 设置「任务运行」页(`SettingsSubPages.DuringWorkScreen` 与 `SettingsScreen`,挂本机开关)
- Modify: `app/src/main/res/values*/strings_device_access.xml`(11 目录,各 +5 键:device_local_group/title/sub/foot/unlock_failed)
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/device/DeviceAccessTest.kt`(+3)
- Test: `app/src/test/java/com/wangzan101/mika/settings/DeviceAccessSettingsTest.kt`(+1)
- Test: `app/src/test/java/com/wangzan101/mika/task/TaskUnlockPolicyTest.kt`(+2)

**Interfaces:**
- Produces: `DeviceAccessStore.localEnabled: Boolean`、`DeviceAccessStore.beginLocalAttempt(): Boolean`(接口默认实现都返回 false);`TaskUnlockPolicy(localUnlockEnabled: () -> Boolean = { false }, …其余不变)`。

- [ ] **Step 1: 写失败测试(core DeviceAccessTest +3)**

- local 独立授权:remote enabled=false、localEnabled=true 时,local 分流 allowed() 为真、remote 分流为假。
- 唤醒途中撤权:allowed() 每次操作前重读——第一次真、第二次假时,流程在凭据输入前中止(fake 凭据注入计数为 0)。
- 熄屏未锁也算:屏幕熄但本来没安全锁时,performedUnlock 为真(我方 `woke || !wasSecure` 语义,补用例钉住)。

- [ ] **Step 2: 写失败测试(app 两处)**

DeviceAccessSettingsTest +1:localEnabled 独立持久化(写 true 不影响 enabled;beginAttempt 与 beginLocalAttempt 的 blocked 占位互不通)。
TaskUnlockPolicyTest +2:Ui 来源授权随 localUnlockEnabled 实时收放(lambda 每次调用重查);Mcp 来源任何开关组合下都不授权。

- [ ] **Step 3: 跑三个测试类确认编译/断言失败**

`./gradlew :core:test --tests '*DeviceAccess*' -q; ./gradlew :app:testDebugUnitTest --tests '*DeviceAccessSettings*' --tests '*TaskUnlockPolicy*' -q`

- [ ] **Step 4: 实现 core + app 授权链**

- `DeviceAccess.kt`:store 接口加两个成员(默认 false);`unlock(authorized)`/`unlockLocal(authorized)` 合并为私有 `unlockUsing(local, …)`,`allowed()`/`prepare()` 按 local 分流,输入凭据前走对应 begin*Attempt;不做「local 提前返回」分支(我方 performedUnlock 语义已覆盖)。
- `DeviceAccessSettings.kt`:config 加 `localEnabled`(默认 false,持久化向后兼容缺省);`setLocalEnabled`(写失败时关回并删文件后抛);`beginLocalAttempt`(独立 blocked 键)。
- `TaskUnlockPolicy.kt`:加 `localUnlockEnabled: () -> Boolean = { false }`;`origin == Ui && localUnlockEnabled()` → 授权(判定 lambda 每次调用重查,不缓存)。
- `TaskRun.kt` 解锁块:`origin ∈ {Ui, Trigger} && settings.localEnabled` 走 unlockLocal;失败文案加 `device_local_unlock_failed` 分支(守卫/回锁/恢复逻辑一律不动)。

- [ ] **Step 5: 设置 UI + 字符串**

- `DeviceAccessSection.kt`:加 `localTasks: Boolean = false` 参数变体(组名/开关行/脚注换 5 个新键文案,凭据编辑区整段复用)。
- 「任务运行」页挂本机开关(传 DeviceAccessSettings,脚注说清与「连接电脑」远程开关互不影响)。
- 11 语言 strings_device_access.xml 各 +5 键。

- [ ] **Step 6: 跑绿全部相关测试**

`./gradlew :core:test --tests '*DeviceAccess*' -q; ./gradlew :app:testDebugUnitTest --tests '*DeviceAccess*' --tests '*TaskUnlockPolicy*' -q`

- [ ] **Step 7: Commit**

`feat(device): 本机任务亮屏解锁 opt-in 开关与授权链`

### Task 4: 全量回归

- [ ] **Step 1:** `./gradlew :core:test :app:testDebugUnitTest --console=plain -q` 全绿(exit 0)
- [ ] **Step 2:** 若 TerminalHubTest 15s 超时单独挂且 diff 不碰 app/terminal:按已知环境抖动处理(记忆 terminalhub-test-local-flake),重跑该类确认
