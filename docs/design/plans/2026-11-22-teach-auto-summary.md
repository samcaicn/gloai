# 教 Mika 自动总结 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 示范结束即自动把轨迹整理成技能:桌面/图标/地址栏这类人工机械步骤折叠成 Mika 的直达做法,总结页直接展示成品技能供用户收下。

**Architecture:** core 里新增确定性的 `TeachRoute.fold`(只折叠启动器步骤,识别失败 fail-open),提示词与 `TeachDraft` 接收折叠后的路线并指示模型把浏览器/搜索机械动作改写成直达;app 层 `TeachApps` 用系统意图识别桌面与默认浏览器,`TeachRuntime.end()` 自动触发整理,`TeachScreen` 改为无状态页面组(介绍/示范中/整理中/总结/已学会),总结正文复用 `SkillReadingView`。

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4, Gradle(`:core` 纯 JVM,`:app` unit/androidTest)。

**Spec:** `docs/superpowers/specs/2026-11-22-teach-auto-summary-design.md`

## Global Constraints

- Android 通用:只看系统意图与包名,禁止写死任何启动器/浏览器/App 包名。
- 不保存示范输入原文;密码字段与敏感命中沿用 `TeachTrace` 现有脱敏,不放开。
- 所有新增/改动的字符串同步 12 个语言文件(base en、zh、b+zh+Hant、ja、ko、ar、fa、es、pt、ru、in、tr);`StringResourcesTest` 必须绿。
- 在 worktree 分支开发,不碰 main;非发版不改版本号。
- 单测用 `./gradlew :app:testDebugUnitTest`(不要裸 `:app:test`,会 OOM);core 用 `./gradlew :core:test`。
- 不新增截图/录屏;不动机器人执行与「任务后自学」管线。

## Review Focus

1. 第三方启动器(Nova、Lawnchair)或识别不到桌面时:路线必须原样保留,不得误删 App 内步骤。
2. 示范从 App 内开始(没有桌面步骤)时:不得凭包名合成「启动」步,技能入口仍要能写对 App。
3. 中途切换 App:每次切换折叠成一条「改用/回到」,不得把桌面点击写进技能,也不得重复堆叠。
4. 草稿已存但会话未落盘(整理中进程被杀):恢复时按 `learned_from=<traceId>` 复用已有 pending 草稿,不重复生成、不丢轨迹。
5. 没有模型或整理失败:总结页给结构化本机技能与明确说明 + 重试/重来,绝不退回原始步骤列表。

---

### Task 1: 交互原型确认(设计闸门,非代码)

**Files:**
- Create: `/tmp/mika-teach-redesign.html`(本地原型,不入库)

**Interfaces:**
- Consumes: 本 spec 的总结页内容与文案。
- Produces: 用户对原型的口头确认;未确认前不进入 Task 5 的 UI 实现。

- [x] **Step 1: 做五态原型**

单页 HTML,复用现有 Mika 视觉 token(浅色/深色各一版,手机宽度):介绍、示范中、整理中、总结(含折叠提示与五节正文)、已学会。可点击在状态间切换。

- [x] **Step 2: 给用户看并等确认**

截图 + 本地文件路径发给用户;明确问「总结页的信息层次和操作对不对」。确认后才开始 Task 5;Task 2–4 可并行开工。

- [x] **Step 3: 记录确认**

2026-11-22 已确认:介绍/整理中/总结/已学会按原型;示范中保留现有实现(顶部提示 + 现有控制),不重做版式。原型留在 `/tmp/mika-teach-redesign.html`,不入库。

---

### Task 2: 路线折叠(确定性)

**Files:**
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/teach/TeachRoute.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/teach/TeachRouteTest.kt`

**Interfaces:**
- Consumes: `TeachStep(id, atMs, packageName, window, kind, nodeName, viewId, insufficient, sensitiveOmitted, needsClarification, ...)`、`TeachKind`、现有 `teachLine(step)`。
- Produces:
  - `data class RouteStep(val app: String?, val text: String, val kind: TeachKind?)` — `kind == null` 表示合成的启动/切换步;`text` 供提示词与人读。
  - `data class RouteApp(val packageName: String, val label: String, val browser: Boolean)`
  - `data class TeachRoute(val steps: List<RouteStep>, val apps: List<RouteApp>, val folded: List<String>)`
  - `object TeachRoute { fun fold(steps: List<TeachStep>, launcherPackages: Set<String>, browserPackages: Set<String>, label: (String) -> String): TeachRoute }`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun foldsLauncherStepsIntoOneDirectLaunch() {
    val steps = listOf(
        step(0, "com.android.launcher3", TeachKind.Window, title = "桌面"),
        step(1, "com.android.launcher3", TeachKind.Click, node = "小红书"),
        step(2, "com.xingin.xhs", TeachKind.Window, title = "首页"),
        step(3, "com.xingin.xhs", TeachKind.Click, node = "搜索"),
    )
    val route = TeachRoute.fold(steps, setOf("com.android.launcher3"), emptySet()) { it }
    assertEquals(2, route.steps.size)
    assertTrue(route.steps[0].text.startsWith("启动"))
    assertTrue(route.steps[1].text.contains("点了「搜索」"))
    assertEquals(2, route.folded.size)
    assertTrue(route.folded.any { it.contains("小红书") })
    assertEquals(listOf("com.xingin.xhs"), route.apps.map { it.packageName })
}

@Test fun appSwitchMidDemoBecomesOneSwitchStep() { /* A → launcher → B ⇒ "改用 B"; 连续桌面步骤只折叠一次 */ }

@Test fun unknownLauncherLeavesEveryStepUntouched() {
    // launcherPackages 为空:保留步骤数与 text 行一一对应,folded 为空
}

@Test fun demoStartedInsideAnAppDoesNotInventALaunchStep() { /* 首步即在 App 内 ⇒ 不合成启动步 */ }

@Test fun marksTheDefaultBrowserForThePrompt() { /* 步骤含浏览器包 ⇒ apps 里 browser=true */ }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.teach.TeachRouteTest'`
Expected: FAIL,`TeachRoute` 未定义。

- [ ] **Step 3: 实现 `TeachRoute.fold`**

规则:遍历步骤;包名在 `launcherPackages` 的直接丢弃并记入 `folded`(用 `teachLine` 的人话,如「桌面点了『小红书』」);当从启动器落到非启动器包、且与上一条保留步骤的包不同时,在保留步骤前插入合成步——首次为「启动 <label>」,切换为「改用 <label>」,回到之前的包为「回到 <label>」;`apps` 按出现顺序去重,`browser` 取 `browserPackages` 命中。`label` 由调用方提供(空标签回退包名)。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/teach/TeachRoute.kt core/src/test/kotlin/com/wangzan101/mika/core/teach/TeachRouteTest.kt
git commit -m "feat(teach): fold launcher steps into direct app launches"
```

---

### Task 3: 提示词与草稿改用折叠路线

**Files:**
- Modify: `core/src/main/kotlin/com/wangzan101/mika/core/teach/Teach.kt:312-421`(`TeachDraft`)
- Modify: `core/src/main/resources/prompts/roles/teach_skill.md`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/teach/TeachDraftTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `TeachRoute / RouteStep / RouteApp`。
- Produces:
  - `TeachDraft.request(session: TeachSession, route: TeachRoute): String`
  - `TeachDraft.assemble(session: TeachSession, route: TeachRoute, name: String): String`
  - `TeachDraft.draft(session: TeachSession, route: TeachRoute, model: ChatModel?, toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String>, nameTaken: (String) -> Boolean, nowMs: Long): PersonalSkill?`
  - 新的 `TeachSkill.PREFIX` 用法不变。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun requestTellsTheModelToUseDirectLaunchesAndLinks() {
    val req = TeachDraft.request(session(), route())
    assertTrue(req.contains("启动 com.xingin.xhs"))
    assertTrue(req.contains("直接打开"))
    assertTrue(req.contains("不要写") && req.contains("桌面"))
}

@Test fun requestCarriesTheFoldedHumanSteps() {
    assertTrue(TeachDraft.request(session(), route()).contains("已按 Mika 的直达能力省掉"))
}

@Test fun localAssemblyNeverMentionsTheDesktop() {
    val md = TeachDraft.assemble(session(), route(), "xhs-search")
    assertFalse(md.contains("桌面"))
    assertTrue(md.contains("启动 com.xingin.xhs"))
}

@Test fun localAssemblyKeepsTheEvidenceCaveats() {
    val md = TeachDraft.assemble(session(), route(), "xhs-search")
    assertTrue(md.contains(TeachSkill.NOT_ABSENT))
    assertTrue(md.contains(TeachSkill.NOT_TRIAL_RUN))
    assertTrue(md.contains(TeachSkill.STOP_IF_UNMATCHED))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests 'com.wangzan101.mika.core.teach.TeachDraftTest'`
Expected: FAIL(编译错:签名不匹配)。

- [ ] **Step 3: 改 `request` 与 `assemble`**

- `request`:输出「目标」「App: <label>(<pkg>)<浏览器? 是>」「已按 Mika 的直达能力省掉:<folded>」和路线步骤(合成步用其 text);删除对原始 launcher 步骤的输出;保留「输入的文字没有保存」。
- `assemble`:入口小节改为「Mika 直接启动 <首个 app>」;步骤用 `route.steps`;成功判据仍取最后一个保留步骤的包名/窗口;caveats 原样保留;首尾不再出现「桌面/图标」。
- `draft`:多一个 `route` 参数并传给二者。

- [ ] **Step 4: 重写提示词 `teach_skill.md` 的规则段**

在现有输出格式与五项要求后追加(替换「不是逐步宏」一条为更具体的):

```
示范者的一些动作只是因为人在做,不算知识:
- 回桌面、找图标、点开 App → 写成「启动/打开 <App>」,不要写桌面和图标。
- 打开浏览器、点地址栏、输入、点搜索 → 写成「打开 <链接>」或「在 <站点> 搜索」;具体链接/关键词由请求提供,示范里没有保存。
- 点输入框、输入、提交 → 合成一步「输入 <内容> 并提交」。
保留真正决定结果的:入口、具体按钮、选项、判据、什么时候停。
同一屏的等待、重复滚动、无意义返回不写。
```

- [ ] **Step 5: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add core/src/main/kotlin/com/wangzan101/mika/core/teach/Teach.kt core/src/main/resources/prompts/roles/teach_skill.md core/src/test/kotlin/com/wangzan101/mika/core/teach/TeachDraftTest.kt
git commit -m "feat(teach): summarize demonstrated routes with direct actions"
```

---

### Task 4: 自动整理与收下

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/teach/TeachApps.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/teach/TeachRuntime.kt`
- Modify: `app/src/main/java/com/wangzan101/mika/ui/TeachScreen.kt`(只改调用点,保证能编译;版式在 Task 5 重做)
- Create: `core/src/main/kotlin/com/wangzan101/mika/core/teach/TeachPending.kt`
- Test: `core/src/test/kotlin/com/wangzan101/mika/core/teach/TeachPendingTest.kt`

**Interfaces:**
- Consumes: Task 2/3;`PersonalSkills.drafts()[id]`、`approve(id)`、`reject(id)`;`ConfirmationNotifier.appLabel(context, pkg)`。
- Produces:
  - `object TeachApps { fun launchers(context: Context): Set<String>; fun defaultBrowsers(context: Context): Set<String>; fun label(context: Context, packageName: String): String }`
  - `object TeachPending { fun find(drafts: Map<String, PersonalSkill>, traceId: String): Pair<String, PersonalSkill>? }`(匹配 `learned_from == TeachSkill.PREFIX + traceId`)
  - `TeachUi(session, notice, hint, draft: PersonalSkill? = null, folded: List<String> = emptyList(), basic: Boolean = false, acceptedName: String? = null)`
  - `TeachSession(..., acceptedName: String? = null)`
  - `TeachRuntime.end()` 自动整理;`TeachRuntime.accept()`;删除 `generate()/annotate()/remove()`。

- [ ] **Step 1: 写 `TeachPending` 失败测试**

```kotlin
@Test fun findsTheDraftAlreadyStoredForTheSameTrace() {
    val drafts = mapOf("d1" to skill(learnedFrom = "teach:t-1", name = "a"), "d2" to skill(learnedFrom = "teach:t-2", name = "b"))
    assertEquals("d1", TeachPending.find(drafts, "t-1")?.first)
}
@Test fun ignoresDraftsFromOtherTraces() { assertNull(TeachPending.find(mapOf("d1" to skill(learnedFrom = "teach:t-2")), "t-1")) }
```

Run: `./gradlew :core:test --tests '*TeachPendingTest'` → FAIL;实现 → PASS。

- [ ] **Step 2: 实现 `TeachApps`**

`launchers`: `ACTION_MAIN + CATEGORY_HOME` 的 `queryIntentActivities(intent, 0)`,异常回退空集。`defaultBrowsers`:`ACTION_VIEW` + `http://` 的 `queryIntentActivities(intent, MATCH_DEFAULT_ONLY)`;空集合法。`label` 直接转 `ConfirmationNotifier.appLabel`。全部 `internal`。

- [ ] **Step 3: 改 `TeachRuntime`**

- `end()`:把 phase 设成 `Drafting` 后立刻 `scope.launch { summarize(snapshot) }`,不再只停在 Reviewing。
- `summarize` = 原 `generate()` 的主体:先 `TeachPending.find(skills.drafts(), snapshot.id)` 复用,否则 `build(snapshot)`;`build` 用 `TeachRoute.fold(snapshot.steps, TeachApps.launchers(app), TeachApps.defaultBrowsers(app), { TeachApps.label(app, it) })`;完成后 phase `Reviewing`、`draftId` 落盘、`_ui` 带上 `draft/folded/basic`(basic = 未配置模型)。
- `accept()`:校验草稿仍在 → `skills.approve(draftId)` → `acceptedName` 写进 session、phase `Saved`、保存并发布;失败给 `TeachNotice.DraftFailed`。
- `recover()`:`Drafting` 与「`Reviewing` 且无 draftId」重新整理;`Reviewing` 有 draftId 时从 `drafts()` 取回并发布;找不到草稿 → 保持 `Reviewing` + TeachNotice.DraftFailed。
- 删除 `generate()/annotate()/remove()` 与 `TeachEdit` 的 UI 引用;`discard()` 仍拒绝草稿并删轨迹。

- [ ] **Step 4: 改 `TeachScreen` 的调用点(临时版)**

去掉 `generate()`/`annotate()`/`remove()` 的调用与逐条编辑器;`Drafting` 显示「正在整理」,`Reviewing` 有草稿时先直接显示 `SkillReadingView` 与一个「收下」按钮(最小可用,Task 5 再按原型重排版式与文案)。

- [ ] **Step 5: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/wangzan101/mika/teach/TeachApps.kt app/src/main/java/com/wangzan101/mika/teach/TeachRuntime.kt app/src/main/java/com/wangzan101/mika/ui/TeachScreen.kt core/src/main/kotlin/com/wangzan101/mika/core/teach/TeachPending.kt core/src/test/kotlin/com/wangzan101/mika/core/teach/TeachPendingTest.kt
git commit -m "feat(teach): summarize automatically after demonstration"
```

---

### Task 5: 页面改版与文案

**Files:**
- Rewrite: `app/src/main/java/com/wangzan101/mika/ui/TeachScreen.kt`
- Modify: `app/src/main/res/values*/strings_teach.xml`(12 个文件)
- Test: `app/src/androidTest/java/com/wangzan101/mika/ui/TeachScreenTest.kt`(新建)

**Interfaces:**
- Consumes: Task 4 的 `TeachUi`、`TeachRuntime.accept/discard/resume/end/pause`;`SkillReadingView(markdown)`、`skillPreviewBody(markdown)`、`FrontMatter.parse`。
- Produces(无状态、可测):
  - `internal data class TeachSummaryView(val display: String, val description: String, val body: String, val folded: List<String>, val basic: Boolean)`
  - `internal fun TeachSummaryPage(view, onAccept, onRedo, onDiscard)`
  - `internal fun TeachIntroPage(goal, hint, notice, onGoal, onStart)`
  - `internal fun TeachDonePage(name, description, onBackToChat, onTeachAgain)`
  - `TeachScreen` 只做状态分发与 runtime 调用。

- [ ] **Step 1: 写 UI 失败测试**

```kotlin
@Test fun summaryShowsTheSkillSectionsAndFoldedNote() {
    compose.setContent { MikaTheme { TeachSummaryPage(fixture(), {}, {}, {}) } }
    compose.onNodeWithText("入口").assertIsDisplayed()
    compose.onNodeWithText("怎样算办成").assertIsDisplayed()
    compose.onNodeWithText("已自动省掉 2 个只用手的步骤").assertIsDisplayed()
    compose.onNodeWithTag("teach-accept").assertHasClickAction()
}
@Test fun summaryWithoutFoldedStepsHidesTheNote() { /* folded = emptyList ⇒ 节点不存在 */ }
@Test fun summaryWithoutModelExplainsTheLocalSummary() { /* basic = true ⇒ 本机整理说明出现 */ }
@Test fun acceptButtonCallsBack() { /* 点 teach-accept ⇒ 回调 1 次 */ }
@Test fun introStartCallsBack() { /* 点 teach-start ⇒ 回调 */ }
@Test fun donePageOffersChatAndTeachAgain() { /* 两个按钮都显示 */ }
```

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.ui.TeachScreenTest`
Expected: FAIL(TeachSummaryPage 未定义)。

- [ ] **Step 2: 写文案(zh 基准)**

新增/替换(`strings_teach.xml`):
- `teach_intro_body`「你先完整做一遍,我在旁边看。做完自动整理成技能,下次直接用。」
- `teach_disclosure_short`「只看这台手机上的操作;密码、验证码、支付内容不记录。详细说明」
- `teach_summarizing`「正在整理成技能…」
- `teach_summary_head`「按 Mika 自己的做法整理,不是把你的操作重放一遍。」
- `teach_folded_n`「已自动省掉 %1$d 个只用手的步骤」
- `teach_basic_note`「这次没接模型,按本机规则整理。配好模型后能理解得更细。」
- `teach_accept`「收下这个技能」
- `teach_redo`「再示范一次」
- `teach_retry`「再整理一次」
- `teach_accepted_title`「学会了」
- `teach_accepted_body`「『%1$s』已经能用。下次说类似的事,我会先想到它。」
- `teach_back_chat`「回对话」
- `teach_teach_again`「再教一件」
- `teach_no_steps_again`「这次没看到操作,再来一次?」
- 删除不再使用的 `teach_review`、`teach_generate`、`teach_correct`、`teach_clarify`、`teach_remove`、`teach_insufficient_note`、`teach_draft_ready`、`teach_open_drafts`。

其余 11 个语言按同样语义翻译;阿拉伯语注意复数形式,所有 `%1$s/%1$d` 占位符保持一致。

- [ ] **Step 3: 重写 `TeachScreen.kt`**

- 介绍:顶部栏 + 小卡说明 + 目标输入(App 现有输入样式)+ `开始示范`;开始后 `moveTaskToBack` 保持现状。
- 示范中与中断:沿用现有 `Live`/`Interrupted` 版式与按钮(已确认不重做),不显示任何步骤列表。
- 整理中:说明文字 + 取消,不显示任何步骤列表。
- 总结:TeachSummaryPage(view, onAccept, onRedo, onDiscard);`display/description` 从 `FrontMatter.parse(draft.markdown)` 取,正文用 `skillPreviewBody`。
- 已学会:TeachDonePage(name, description, onBackToChat, onTeachAgain);回对话用 `onBack`。
- 再示范:`runtime.discard()`,再把目标 `prefill` 回介绍。
- `testTag`:`teach-form/teach-goal/teach-start/teach-summary/teach-folded/teach-accept/teach-redo/teach-discard/teach-done`。

- [ ] **Step 4: 跑测试与 i18n**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.ui.TeachScreenTest` → PASS。
Run: `./gradlew :app:testDebugUnitTest` → PASS(含 `StringResourcesTest`)。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/wangzan101/mika/ui/TeachScreen.kt app/src/main/res/values*/strings_teach.xml app/src/androidTest/java/com/wangzan101/mika/ui/TeachScreenTest.kt
git commit -m "feat(teach): show an auto-written skill instead of raw steps"
```

---

### Task 6: 真机端到端与回归

**Files:** 无代码改动(除非发现 bug)。

**Interfaces:**
- Consumes: 前五任务全部产物。
- Produces: 设备验证结论;发现的 bug 回到对应任务修。

- [x] **Step 1: 安装并跑真机示范**

`./gradlew :app:installDebug`;在 A94VUT2C28001694 上从教 Mika 开始,示范「打开小红书 → 搜索装修 → 打开第一条 → 收藏 → 做完了」。截图确认:总结第一步为「启动 小红书」,不含「桌面/图标」;折叠提示数量合理;正文五节齐全。

- [x] **Step 2: 收下与可用性**

点「收下这个技能」→ 已学会页;进 设置 › 记忆 › 技能,确认它以试用状态存在;回到对话说「用刚才学的技能再搜一次」,确认技能能被检索/读到。

- [ ] **Step 3: 网页示例(本机受限,未能在真机完成)**

再示范一次网页操作(默认浏览器搜索),确认总结写成「打开/搜索」,不含地址栏步骤;若模型未改写,记录 prompt 证据并修 Task 3。

本机实测:Edge/WebView 页面里的点按不产生 TYPE_VIEW_CLICKED,只演示浏览器时采集到的全是 Window 步骤,折叠后没有真实操作、页面报「没有留下步骤」(已加窗口-only 的单测锁住这一行为)。浏览器改写规则目前只由 `TeachDraftTest` 覆盖;等采集层能收到 WebView 事件后再补真机这一条。

- [x] **Step 4: 失败与恢复**

- 0 步:开始后立刻结束 → 显示「没看到操作」,可重来。
- 整理中被杀:整理中按返回/强杀 Mika,重进教 Mika → 自动继续整理或显示重试,轨迹不丢。
- 无模型:临时切到一个未配置模型的资料?若成本高,直接用 Task 3 的本机整理单测 + 代码审阅代替,并在真机只看有模型路径。

- [x] **Step 5: 回归**

Run: `./gradlew :core:test :app:testDebugUnitTest` → PASS。
Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.ui.TeachScreenTest,com.wangzan101.mika.ui.ChatAttachmentsTest` → PASS。
Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` → PASS。
不做全量 connectedAndroidTest。

- [x] **Step 6: 提交(如有小修)**

```bash
git add -A
git commit -m "fix(teach): device-test follow-ups"
```
