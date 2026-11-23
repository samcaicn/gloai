# 设置首页信息精简 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已确认的设置首页原型落地为单行入口，同时保留所有动态状态和异常处理信息。

**Architecture:** 只修改首页 `ListRow` 调用点和成功状态卡的参数，不改共享组件。二级、三级页面继续按原样使用各自副标题。

**Tech Stack:** Kotlin、Jetpack Compose、Android Compose UI instrumentation tests。

**Spec:** `docs/design/specs/2026-11-22-settings-home-clarity-design.md`

## Global Constraints

- 只改设置首页，不处理其他页面。
- 保留功能、分类、导航、动态值和异常状态说明。
- 不删除仍被其他页面使用的字符串资源。
- 不提交代码，除非用户另行要求。

## Review Focus

- 成功状态卡应变简洁，但异常状态仍有问题详情和修复动作；由现有 `statusCardTurnsIntoFixPrompt` 覆盖。
- 所有可选入口同时出现时也不能残留静态副标题；由本任务新增测试覆盖。
- 模型、手机操作、通知、版本、胶囊和归档等动态值不能消失；由现有状态测试和新增断言覆盖。
- 首页点击与开关行为不能受视觉删减影响；由现有 `homeRowsOpenTheirPages` 覆盖。
- 任务运行和其他子页的说明不能改变；通过最终 diff 限定文件与调用点，并运行整个 `SettingsScreenTest`。

---

### Task 1: 首页静态副标题精简

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsScreen.kt:477-580,714-727`
- Test: `app/src/androidTest/java/com/wangzan101/mika/settings/ui/SettingsScreenTest.kt`

**Interfaces:**
- Consumes: 现有 `SettingsContent`、`StatusSummary`、`ListRow`、`StateCard` 接口。
- Produces: 不新增或改变任何接口；仅减少首页传入的可选副标题。

- [ ] **Step 1: 写入失败测试**

新增 `homeOmitsStaticEntrySubtitles`，用全部可选回调、`onPersonalStart`、`autoArchiveDays = 7` 渲染首页。断言以下文字均不存在：成功状态详情、模型目录、通话方式、微信说明、主动能力说明、任务运行目录、胶囊说明、人格说明、通知说明、个人助手复述、记忆目录、备份说明、归档说明、常用任务说明、应用访问说明、短信默认状态、高级目录。同时断言 `DeepSeek`、`可用`、`正常`、`7 天` 和版本值仍存在。

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
ANDROID_SERIAL=A94VUT2C28001694 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.settings.ui.SettingsScreenTest
```

Expected: 新增测试因首页仍显示静态副标题而失败。

- [ ] **Step 3: 实现首页精简**

在 `SettingsContent` 中省略模型、AI 接打电话、微信、主动能力、任务运行、悬浮胶囊、人格、通知、启动时打开个人助手、记忆与技能、备份与数据、自动归档对话、常用任务、应用访问、短信验证码登录、高级入口的静态副标题。保留 `first`、回调、开关和 trailing 参数。`StatusSummary` 的成功分支调用 `StateCard` 时不再传 `settings_all_ready_detail`，异常分支不变。

- [ ] **Step 4: 运行目标测试并确认通过**

Run:

```bash
ANDROID_SERIAL=A94VUT2C28001694 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.settings.ui.SettingsScreenTest
```

Expected: `SettingsScreenTest` 全部通过，包括新增精简测试、原导航测试和异常状态测试。

- [ ] **Step 5: 检查改动范围**

Run: `git diff --check` 与 `git diff --stat`，确认无空白错误，产品代码只改 `SettingsScreen.kt`，测试只改 `SettingsScreenTest.kt`，没有二级、三级页面改动。不提交。
