# 取证时间线（真机：华为 Mate X2，TET-AN50，API 31，2026-11-21）

| 时刻 | 操作 | 结果 |
|---|---|---|
| 17:32 | `./gradlew :app:assembleDebug` 构建含改动的 debug 包 | BUILD SUCCESSFUL |
| 17:33 | 装包前核查：最近运行记录最新为 2026-11-20（无活跃任务）、截屏确认前台为空聊天首页 | 安全，可操作 |
| 17:33 | `adb install -r app-debug.apk` | Success；首启弹「语言偏好」引导，点「暂不设置」保持原设置 |
| 17:34 | 侧栏 › 设置 › 任务运行 | 新行「每个任务的沙箱时间上限」出现在「定时任务最长运行时间」之后，值为默认 **30 分钟**（截图 01） |
| 17:34 | 点该行 | 弹窗标题 + 说明文案 + 四档单选：30 / 60 / 120 / 240 分钟（截图 02） |
| 17:35 | 选「240 分钟」 | 行值回写为 **240 分钟**（截图 03）；`run-as … cat shared_prefs/feature_flags.xml` 显示 `sandbox_budget_limit_minutes" value="240"` |
| 17:36 | 重新选回「30 分钟」 | prefs 回 `value="30"`，设备恢复默认状态，退出设置 |

默认行为不变与守卫数学（默认/自定义档的拒绝时机、消息分钟数、capTimeout 夹取）由 `SandboxGuardTest`（core，5 例）与 `FeatureFlagsSandboxBudgetTest`（app，1 例）覆盖；本地全量四组测试见下。

## 本地测试（2026-11-21）

- `./gradlew :core:test`：全绿（含新增 SandboxGuardTest 5 例）
- `./gradlew :core:cliTest`：OK
- `python3 -m unittest discover -s tools/i18n`：12 例 OK；`python3 tools/i18n/translations.py check`：3718 resources / 12 语言通过
- `python3 -m unittest discover -s tools -p 'test_pull_logs.py'`：OK
- `./gradlew :app:testDebugUnitTest`：全绿（首轮 TerminalHubTest.deletingProjectClosesSharedProjectTerminal 一次 15s 超时 flake，单独重跑与整组重跑均绿，与本次改动无关——该用例预算 2s→15s 是维护者刚在 3b513b1e 调过的时序敏感项）
- `./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin`（CI app 组构成）：通过
- `./gradlew :core:relayTest`（Go 组）：BUILD SUCCESSFUL

## 设备用例

未跑（本变更不涉设备用例；本机 instrumentation 因无障碍绑定挂起不可用，见 AGENTS.md——连续两轮验证过该 ROM 状态，重启才能清而 PIN 锁 adb 解不开）。
