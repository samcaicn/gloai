# 设置二级/三级页副标题精简 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline) to implement task-by-task, TDD per task.

**Goal:** 按已确认的高保真 demo（`.superpowers/sdd/settings-secondary-demo.html`），把设置页 6 个二级/三级页里「复述标题/罗列目录/静态默认值」的副标题删掉，把「当前值/数量/状态」移到行尾值，保留「风险后果/触发条件/选项差异/系统路径」。

**Architecture:** 全部改动都在现有 Compose 组件里「少传一个 subtitle 参数」或「把 subtitle 参数换到 trailing RowValue」，不改任何接口、不新增字符串。

**Tech Stack:** Jetpack Compose, Kotlin, stringResource / tr 多语言资源（`values*/strings_*.xml`）。

**Spec:** `docs/design/specs/2026-11-22-settings-home-clarity-design.md`（首页设计）；二级页视觉决策见 demo 文件。

## Global Constraints

- 不改分类、图标、功能、层级；只动副标题与行尾值。
- **本计划不新增任何字符串**（需要新文案的「缩短副标题」与「恢复页折叠」见末尾 Ruling，另行处理）。
- 不提交（沿用仓库规矩：仅在被明确要求时提交）。
- 测试跑物理机 `A94VUT2C28001694`（`ANDROID_SERIAL=A94VUT2C28001694`），不是模拟器。

## 判断口径（demo 已确认）

- **删除**：复述标题、罗列详情页目录、静态默认值。
- **右移**：当前值、数量、开关/连接状态。
- **保留**：风险后果、触发条件、选项差异、系统路径。

## 任务拆分

### Task 1: 手机操作（`SettingsSubPages.kt` `PhoneOpsScreen`）
- 删 `settings_a11y_sub`（无障碍服务副标题）。
- 删 `phone_repair_sub`（修复连接副标题）。
- Shizuku 诊断 `phone_shizuku_detail` 仅在未就绪时显示。
- 系统版本：正常时行尾值由「满足」改为版本号（`settings_android`），副标题置空；`tooOld`/`focusWarning` 分支不动。
- **保留**：内置助手 `statusLine`（动态诊断）、`phone_unpair_sub`（危险后果）。

### Task 2: 任务运行（`SettingsSubPages.kt` `DuringWorkScreen`）
- 删 `settings_keep_on_sub`、`settings_mute_sub`、`settings_show_task_cost_sub`、`settings_pip_operate_sub`。
- 步数上限 / 定时任务最长运行时间 / 沙箱时间上限 / Token 预算 4 个当前值：由副标题位 → 行尾 `RowValue` + `RowChevron`。
- **保留**：自主模式（付款风险）、口袋防误触（触发条件）、操作节奏三档差异、按应用设置说明、并行任务原因。

### Task 3: 悬浮胶囊（`SettingsSubPages.kt` `CapsuleScreen`）
- 删 `capsule_idle_opacity_detail`、`capsule_card_size_detail`。
- **保留**：三模式差异说明、两条页脚风险说明。

### Task 4: 通知（`SettingsSubPages.kt` `NotificationsScreen`）
- 删总开关副标题（`NOTIF_APP_SUBTITLE_ON/OFF`）。
- 删 `settings_scheduled_quiet_detail`。
- **保留**：三个频道的触发时机、NOTIF_FOOTER 系统路径。

### Task 5: 恢复与修复（`RecoverySection.kt` `RecoverySection`）
- 删 `rec_default_on`（「默认打开」）；`WORKING`（忙时反馈）保留。

### Task 6: 高级（`SettingsScreen.kt` `AdvancedSettingsContent`）
- 删 `web_search_row_sub`。
- **保留**：网络代理 `proxyNote`（动态范围说明）、Root `rootEntryLine`（组合状态，见 Ruling）、`quit_row_sub`（后果）。

## Ruling（决策记录，执行时写进 ledger）

- **R1** 内置助手 `statusLine` 保留：它是动态诊断（已连接/失败原因），不是静态复述。
- **R2** Root `rootEntryLine` 保留：它是「自动启动助手 + root 命令状态」的组合摘要，塞进行尾值过窄。
- **R3** 网络代理 `proxyNote` 保留：动态说明它管哪些连接、浏览器是否支持。
- **R4** 4 处「缩短副标题」（`pocket_completion_lock_summary`、`settings_self_config_sub`、`settings_self_operate_sub`、`settings_external_schedule_sub`）暂缓：需新文案 ×12 语言，另做。
- **R5** 恢复页「把 4 段说明折叠成可展开详情」暂缓：需新文案 ×12 语言 + 折叠状态，另做。

## Review Focus

- 系统版本行：`tooOld`（安卓 12 以下）时仍显示「不支持」，不能因为删副标题把「不支持」信号弄丢。
- Shizuku 行：未就绪时诊断仍要出现，就绪时才隐藏——不能一刀切隐藏导致异常状态无提示。
- 恢复页 `busy` 状态：拨动开关时的「正在…」反馈不能因删「默认打开」一起消失。
- 任务运行 4 个值：值从副标题右移后仍要能点开对话框改（保留 `onClick`）。
- 通知总开关：`o.appAllowed` 为 false 时「去打开」链接仍要可点。
