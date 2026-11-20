# fix-keypool-auth-exhaustion 证据

## 2026-11-20 本地验证（JVM 层）

基线：`main` @ `0c258ac9`（#747 已入、未发版）；分支 `fix/keypool-auth-exhaustion`。

1. **缺陷定位**
   - `grep -rn markExhausted --include="*.kt" core/ app/`：生产代码零调用（仅 `KeyPoolManagerTest` 用到）。
   - `ProviderStore.buildModel` 两处接线（OpenAiCompat/Gemini）对 429/401/403 一律 `markRateLimited`（60s 默认冷却），坏 key 到期复活、反复被轮询选中。
   - #747 提交信息写明 "Automatically mark rate-limited **or exhausted** keys on HTTP 429, 401, 403"，耗尽语义从未接线。

2. **修复后测试**（`./gradlew` 本地，JDK 17）
   - `:core:test --tests …KeyPoolManagerTest --tests …OpenAiCompatModelTest --tests …GeminiModelTest`：通过（新增 5 个用例：401→耗尽不复活、403→耗尽、429→冷却可恢复、OpenAi 回调带状态+换 key、Gemini 回调带状态+换 key）。
   - `:app:testDebugUnitTest --tests …ProviderStoreTest`：90/90 通过（新增真接线用例 `authFailureMarksKeyExhaustedThroughWiredModel`：MockWebServer 回 401/200，断言 `sk-bad` 标耗尽后 `getActiveKey` 只剩 `sk-good`、两笔请求 Authorization 分别是 sk-bad/sk-good）。
   - 全量回归：`:core:test :app:testDebugUnitTest`、`:core:cliTest`、`python3 -m unittest discover -s tools/i18n`（12 OK）、`python3 tools/i18n/translations.py check`（3667 resources OK）全部通过。

3. **修复前行为复现**（同用例在 main 上的语义）
   - main 上无任何测试覆盖该接线；修复前 401 只进 60s 冷却（`KeyPoolManagerTest.rateLimitedKeyBypassedAndAutoRecovers` 的行为对 401 同样成立），到期后 `getActiveKey` 复选坏 key。

## 说明

- 模型 HTTP 层为纯 JVM 代码，无设备特定行为，未做真机专项（真机 provider 配置属用户日常数据，按惯例不动）。
- 与开放 PR #758（`requestWithKeyRotation` 重构）改动同区不重叠：其保持两参回调、未做状态分流；若其先合入需小 rebase。
