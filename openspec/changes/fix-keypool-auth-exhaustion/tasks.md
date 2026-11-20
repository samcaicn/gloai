## 1. 模型回调带上 HTTP 状态

- [x] 1.1 `GeminiModel`：`onRateLimit: ((String, Long?) -> Unit)?` 改为 `onKeyRejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)?`，complete / completeStreaming 两处回调传入实际状态码；换 key 重试逻辑不动。
- [x] 1.2 `OpenAiCompatModel`：同 1.1（complete / completeStreaming 共两处调用点）。

## 2. 接线处按状态分流

- [x] 2.1 `KeyPoolManager.markRejected(providerId, key, status, retryAfterMs)`：429 → `markRateLimited`，401/403 → `markExhausted`；`ProviderStore.buildModel` 两处接线改调它。
- [x] 2.2 `activeApiKey` 全池耗尽时回落第一个 key 的现状保持（单坏 key 如实报错，测试 `authFailureMarksKeyExhaustedThroughWiredModel` 未改动该路径）。

## 3. 测试

- [x] 3.1 `:core` 模型层：MockWebServer 返回 401/403/429，断言回调收到对应状态码并换 key 重试（OpenAiCompatModelTest +2、GeminiModelTest +1）。
- [x] 3.2 `:app` 接线层：`ProviderStoreTest.authFailureMarksKeyExhaustedThroughWiredModel`——真 buildModel 走 MockWebServer，401 后池里只剩好 key。
- [x] 3.3 回归：`./gradlew :core:test :app:testDebugUnitTest :core:cliTest` + `tools/i18n` 12 项 + `translations.py check` 全绿。

## 4. 验证与收尾

- [x] 4.1 mock 端点行为证据落在 `evidence/evidence.md`（模型 HTTP 层为纯 JVM 代码，无真机专项）。
- [ ] 4.2 提交分支、推 fork、开 PR（标题按「现状 → 改动」惯例），等 CI 四组全绿。
