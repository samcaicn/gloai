# fix-ja-step-reason-hidden 取证

日期：2026-11-21 · 分支：`fix/ja-step-reason-hidden`（基于 main 7101e997，v0.1.50）

## 缺陷定位（读码 + 字符区间事实）

- 引入提交：f010acab（#765，v0.1.50 发布）。
- `ChatViewModel.kt` 原 `shouldHideRawReason`：非中文界面下 `reason.any { it in '\u4e00'..'\u9fa5' }` 即隐藏。
- `languageDirective("ja")`（同提交引入）要求模型用日语写全部用意；日语书面语必混汉字（検索/設定/連絡先…，均落在 U+4E00–9FA5），假名（U+3040–30FF）是唯一可靠的日/中字符级判别信号。
- 结论：日语界面下模型按指令写的用意几乎恒被隐藏，`StepRow.text`（步骤行与收起态摘要两处入口）退化为本地化标题。

## 行为矩阵（修前 → 修后）

| 界面 | 用意 | 修前 | 修后 |
|---|---|---|---|
| ja | 日语（漢字+仮名） | 隐藏 | 显示 |
| en 等非中文 | 中文 | 隐藏 | 隐藏（不变） |
| en 等非中文 | 日语 | 隐藏 | 显示（罕见违约场景，显示信息损失更小） |
| zh 简/繁 | 任意 | 显示 | 显示（不变） |
| 非中文 | 纯汉字无假名（設定確認） | 隐藏 | 隐藏（无法与中文区分，如实保留） |

## 测试验证

新增/修改：`app/src/test/java/com/wangzan101/mika/ui/StepRowLanguageTest.kt`
- `testShouldKeepJapaneseReasonInNonChineseLocale`：非中文界面「検索ボタンをタップして連絡先を探す」「「微信」アプリを開く」不隐藏；中文界面日语照常显示。
- `testShouldHideKanjiOnlyReasonInNonChineseLocale`：「設定確認」仍隐藏（残余局限）。
- `testStepRowTextShowsJapaneseReasonUnderJapaneseUi`：ui_locale=ja 时 `StepRow.text` 直接返回日语用意（端到端断言）。

**反事实验证**（防空转测试）：把判别式临时翻回旧版 `reason.any { it in '\u4e00'..'\u9fa5' }` 后，
`StepRowLanguageTest > testShouldKeepJapaneseReasonInNonChineseLocale FAILED`、
`StepRowLanguageTest > testStepRowTextShowsJapaneseReasonUnderJapaneseUi FAILED`（BUILD FAILED in 8s）；
恢复修复版后同类全绿（BUILD SUCCESSFUL in 6s）。临时改动用字符串精确替换进/退，未用 git checkout，事后 `git diff` 复核仅剩正式改动（4 行实现 + 34 行测试）。

## 全量本地测试

- `./gradlew :core:test :core:cliTest :app:testDebugUnitTest` → BUILD SUCCESSFUL（5m；cli 89 tests OK）
- `python3 -m unittest discover -s tools/i18n` → 12 tests OK
- `python3 tools/i18n/translations.py check` → 3699 resources across 12 UI languages passed
- relayTest（Go）未本地跑：改动只涉及 app 侧显示判别，不触 core/relay 代码，随 CI 复核。

## 未做部分（如实）

- 未做真机走查：需日语界面 + 真实模型按日语输出用意的端到端任务，成本高且依赖模型行为；判别逻辑已由注入 ui_locale 桩的单测（含反事实）覆盖。
