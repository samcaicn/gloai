## Context

#747（未发版）引入密钥池：`KeyPoolManager` 按 429 限流冷却（`markRateLimited`，默认 60 秒后自动解冻）与 401/403 永久耗尽（`markExhausted`）区分两类失败。模型侧 `GeminiModel` / `OpenAiCompatModel` 在 429/401/403 三种状态回调 `onRateLimit(key, retryAfterMs)` 并就地换下一个 key 重试一次；`ProviderStore.buildModel` 把回调接到 `KeyPoolManager.shared.markRateLimited`——三种状态一律冷却。

`markExhausted` 因此成为死代码（仅测试调用）。失效 key 60 秒复活 → 轮询选中 → 401 → 再冷却，循环不止。回调缺 HTTP 状态参数，接线处无法分流。

kkkaqi 的开放 PR #758 把模型内"换 key 重试"重构成 `requestWithKeyRotation`，但回调仍是两参、仍全量 `markRateLimited`——本缺口未被认领。本变更基于 main 独立实现；若 #758 先合入，rebase 时把状态参数带进其新helper即可（改动点同源，机械合并）。

## Goals / Non-Goals

**Goals:**
- 401/403 的 key 在池中被标记 `Exhausted`，不再自动复活；429 行为不变。
- 回调携带 HTTP 状态码，命名如实（`onKeyRejected`）。
- `:core` 与 `:app` 单测覆盖：429→冷却、401/403→耗尽、单 key 401 仍如实抛错。

**Non-Goals:**
- 不改轮询/解冻/重注册策略（kkkaqi #757 在做）。
- 不改模型内换 key 重试的结构（kkkaqi #758 在做）。
- 不给 429 加"配额耗尽也永久化"的猜测分类——Retry-After 语义保持现状。

## Decisions

1. **回调加 `status: Int` 并改名 `onKeyRejected`**——#747 未发版，无兼容负担；原名 `onRateLimit` 对 401/403 是误导。
2. **分流放在 `ProviderStore` 接线处**（429 → `markRateLimited`，401/403 → `markExhausted`），模型侧只如实上报状态，不做策略——保持 `:core` 模型类与池策略解耦，`KeyPoolManagerTest` 已有的 401→耗尽用例即此契约。
3. **`Exhausted` 的复活路径维持现状**（重存配置时 key 文本不变则状态保留）：与 #757"重注册保留状态"的方向一致；全部 key 耗尽时 `activeApiKey` 落回第一个 key 如实报错，行为与单坏 key 一致。

## Risks / Trade-offs

- **与 #758 的文本冲突**：同一批函数，若其先合入需小 rebase；改动小、方向正交（他重构重试结构，本变更只改回调签名与接线），冲突可机械解决。
- **个别网关偶发 401**（鉴权后端抖动）会把 key 永久标记：与 `KeyPoolManager` 自己的文档语义（"401 Unauthorized / Invalid Key → permanently Exhausted"）一致，用户重存 key 或重进设置可重置；换来失效 key 不再每分钟毒化轮询，净收益为正。
