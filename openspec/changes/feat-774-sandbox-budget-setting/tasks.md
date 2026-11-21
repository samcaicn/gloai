## 1. core：守卫与透传

- [x] 1.1 `SandboxGuard.exhausted()` 消息按 `budgetMs` 动态输出分钟数；新增 `SandboxGuardTest`：默认/自定义预算下未满不拒、满则拒（error 码、usedMs、消息含实际分钟数）、`capTimeout` 压到剩余时间且不动合法请求
- [x] 1.2 `SandboxCapability` 加 `budgetMs: Long = SANDBOX_BUDGET_MS` 构造参数并用于 `guard()`；`TaskTools.toolDispatcher` 加 `sandboxBudgetMs` 参数透传（默认值不变，其他调用方零改动）
- [x] 1.3 `./gradlew :core:test` 全绿

## 2. app：存储与接线

- [x] 2.1 `FeatureFlags` 新增 `sandboxBudgetMinutes()`/`setSandboxBudgetMinutes()`（档位 30/60/120/240，0/非法=默认 30）；新增 `FeatureFlagsSandboxBudgetTest`：默认、档位往返、非法值回落
- [x] 2.2 `TaskRun` 主任务与子任务两处 `toolDispatcher` 调用传入 `sandboxBudgetMs`

## 3. app：设置入口与文案

- [x] 3.1 `SettingsSubPages.DuringWorkScreen` 加参数、`ListRow`（显示当前档位分钟数）与 `SandboxBudgetDialog`（RadioRow 档位，无「不限制」）；`SettingsScreen` 接线状态与回调
- [x] 3.2 `strings_settings.xml`（values + 全部语言目录）加 `settings_sandbox_budget`、`settings_sandbox_budget_sub`、plurals `settings_sandbox_budget_minutes_n`；`python3 tools/i18n/translations.py check` 通过
- [x] 3.3 `./gradlew :app:testDebugUnitTest` 全绿

## 4. 验证与交付

- [x] 4.1 本地全量：`:core:test :core:cliTest :app:testDebugUnitTest` + cli 组的 i18n 两项检查
- [x] 4.2 真机冒烟：默认档行为不变 + 设 240 后新任务生效（logcat/运行记录取证，放 evidence/）
- [ ] 4.3 `openspec validate`；分支、提交（中文）、PR（关联 #774，附测试结果与证据说明）
